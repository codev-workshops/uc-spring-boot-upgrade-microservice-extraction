package io.spring.infrastructure.extraction.tag;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.spring.application.tag.dto.ArticleTagsRowDto;
import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Consumer-side check of {@link ArticleServiceClient} against the canonical internal Tag API; the
 * JSON fixtures under {@code src/test/resources/article-service-stubs} mirror phase-3-tag.md
 * section 2.1.
 */
public class ArticleServiceClientTest {
  private static final String BASE = "http://localhost:8083";
  private static final String A1 = "a1000000-0000-0000-0000-000000000001";
  private static final String A2 = "a2000000-0000-0000-0000-000000000002";

  private ArticleServiceClient client;
  private MockRestServiceServer server;

  @BeforeEach
  public void setUp() {
    client =
        new ArticleServiceClient(
            new RestTemplateBuilder(), new ExtractionProperties(), new AuthTokenPropagator());
    server = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void all_tags_is_read_without_credentials_and_keeps_row_order() {
    server
        .expect(once(), requestTo(BASE + "/internal/tags"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("tags-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(Arrays.asList("java", "spring", "sqlite"), client.allTags());
    server.verify();
  }

  @Test
  public void empty_tags_is_returned_as_empty() {
    server
        .expect(once(), requestTo(BASE + "/internal/tags"))
        .andRespond(withSuccess(stub("tags-empty-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertTrue(client.allTags().isEmpty());
    server.verify();
  }

  @Test
  public void tags_by_article_ids_sends_one_comma_separated_batch() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/tags?articleIds=" + A1 + "," + A2))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("article-tags-response.json"), MediaType.APPLICATION_JSON));

    List<ArticleTagsRowDto> rows = client.tagsByArticleIds(Arrays.asList(A1, A2));

    server.verify();
    Assertions.assertEquals(2, rows.size());
    Assertions.assertEquals(A1, rows.get(0).getArticleId());
    Assertions.assertEquals(Arrays.asList("java", "spring"), rows.get(0).getTagList());
    Assertions.assertEquals(A2, rows.get(1).getArticleId());
    Assertions.assertTrue(rows.get(1).getTagList().isEmpty());
  }

  @Test
  public void empty_batch_is_not_sent() {
    Assertions.assertTrue(client.tagsByArticleIds(Collections.emptyList()).isEmpty());
    server.verify();
  }

  @Test
  public void article_ids_by_tag_encodes_the_name() {
    server
        .expect(once(), requestTo(BASE + "/internal/tags/java/article-ids"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(stub("article-ids-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/tags/c%23%20sharp/article-ids"))
        .andRespond(
            withSuccess(stub("article-ids-empty-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(Arrays.asList(A1, A2), client.articleIdsByTag("java"));
    Assertions.assertTrue(client.articleIdsByTag("c# sharp").isEmpty());
    server.verify();
  }

  @Test
  public void set_tags_puts_the_monolith_generated_ids_with_the_callers_token_and_is_not_retried() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1 + "/tags"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(stub("set-tags-request.json"), true))
        .andRespond(withNoContent());

    client.setTags(
        A1,
        Arrays.asList(
            tag("t1000000-0000-0000-0000-000000000001", "java"),
            tag("t2000000-0000-0000-0000-000000000002", "spring")));
    server.verify();
  }

  @Test
  public void set_tags_failure_is_surfaced_without_retry() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1 + "/tags"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    Assertions.assertThrows(
        ArticleServiceException.class,
        () -> client.setTags(A1, Collections.singletonList(new Tag("java"))));
    server.verify();
  }

  @Test
  public void reads_retry_once_on_connect_failure() {
    server
        .expect(times(2), requestTo(BASE + "/internal/tags"))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new ConnectException("Connection refused"));
            });

    Assertions.assertThrows(ArticleServiceException.class, () -> client.allTags());
    server.verify();
  }

  @Test
  public void reads_retry_once_on_503_and_succeed() {
    server
        .expect(once(), requestTo(BASE + "/internal/tags"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/tags"))
        .andRespond(withSuccess(stub("tags-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(3, client.allTags().size());
    server.verify();
  }

  @Test
  public void read_timeout_and_5xx_are_not_retried() {
    server
        .expect(once(), requestTo(BASE + "/internal/tags"))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new SocketTimeoutException("Read timed out"));
            });
    Assertions.assertThrows(ArticleServiceException.class, () -> client.allTags());
    server.verify();

    server.reset();
    server
        .expect(once(), requestTo(BASE + "/internal/tags/java/article-ids"))
        .andRespond(withServerError());
    ArticleServiceException e =
        Assertions.assertThrows(
            ArticleServiceException.class, () -> client.articleIdsByTag("java"));
    Assertions.assertTrue(e.getMessage().contains("status 500"));
    server.verify();
  }

  private static Tag tag(String id, String name) {
    Tag tag = new Tag(name);
    tag.setId(id);
    return tag;
  }

  private void withIncomingToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  static String stub(String name) {
    try (InputStream in =
        ArticleServiceClientTest.class
            .getClassLoader()
            .getResourceAsStream("article-service-stubs/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing stub " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
