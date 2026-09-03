package io.spring.infrastructure.extraction.article;

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

import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import io.spring.application.article.dto.ArticleIdsPageDto;
import io.spring.application.article.dto.ArticleRowDto;
import io.spring.application.article.dto.ArticlesDto;
import io.spring.core.article.Article;
import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.tag.ArticleServiceException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
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
 * Consumer-side check of {@link ArticleDomainServiceClient} against the canonical internal Article
 * API; the JSON fixtures under {@code src/test/resources/article-service-stubs} mirror
 * phase-4-article.md section 2.1.
 */
public class ArticleDomainServiceClientTest {
  private static final String BASE = "http://localhost:8083";
  private static final String A1 = "a1000000-0000-0000-0000-000000000001";
  private static final String A2 = "a2000000-0000-0000-0000-000000000002";
  private static final String U1 = "u1000000-0000-0000-0000-000000000001";

  private ArticleDomainServiceClient client;
  private MockRestServiceServer server;

  @BeforeEach
  public void setUp() {
    client =
        new ArticleDomainServiceClient(
            new RestTemplateBuilder(), new ExtractionProperties(), new AuthTokenPropagator());
    server = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void find_by_id_and_slug_read_without_credentials_and_map_404_to_empty() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("article-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/by-slug/java-article"))
        .andRespond(withSuccess(stub("article-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/by-slug/nope"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    ArticleRowDto row = client.findById(A1).get();
    Assertions.assertEquals("java-article", row.getSlug());
    Assertions.assertEquals(U1, row.getUserId());
    Assertions.assertEquals(Arrays.asList("java", "spring"), row.getTagList());
    Assertions.assertEquals("2024-01-03T00:00:00.000Z", row.getCreatedAt());
    Assertions.assertEquals(A1, client.findBySlug("java-article").get().getId());
    Assertions.assertEquals(Optional.empty(), client.findBySlug("nope"));
    server.verify();
  }

  @Test
  public void find_by_ids_sends_one_batch_and_skips_empty_batches() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles?ids=" + A1 + "," + A2))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(stub("articles-response.json"), MediaType.APPLICATION_JSON));

    List<ArticleRowDto> rows = client.findByIds(Arrays.asList(A1, A2));
    Assertions.assertEquals(Arrays.asList(A1, A2), ids(rows));
    Assertions.assertTrue(rows.get(1).getTagList().isEmpty());
    Assertions.assertTrue(client.findByIds(Collections.emptyList()).isEmpty());
    server.verify();
  }

  @Test
  public void query_ids_passes_resolved_filters_and_offset_paging() {
    server
        .expect(
            once(),
            requestTo(
                BASE
                    + "/internal/articles/ids?tag=c%23%20sharp&authorId="
                    + U1
                    + "&ids="
                    + A1
                    + ","
                    + A2
                    + "&offset=20&limit=10"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(stub("article-ids-page-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/ids?offset=0&limit=20"))
        .andRespond(withSuccess("{\"articleIds\":[],\"count\":0}", MediaType.APPLICATION_JSON));

    ArticleIdsPageDto page =
        client.queryIds("c# sharp", U1, Arrays.asList(A1, A2), new Page(20, 10));
    Assertions.assertEquals(Arrays.asList(A1, A2), page.getArticleIds());
    Assertions.assertEquals(5, page.getCount());
    Assertions.assertEquals(0, client.queryIds(null, null, null, new Page(0, 20)).getCount());
    server.verify();
  }

  @Test
  public void cursor_ids_send_limit_direction_and_millis_cursor() {
    DateTime cursor = new DateTime(2024, 1, 2, 0, 0, DateTimeZone.UTC);
    server
        .expect(
            once(),
            requestTo(
                BASE
                    + "/internal/articles/ids/cursor?tag=java&limit=20&direction=prev&cursor="
                    + cursor.getMillis()))
        .andRespond(
            withSuccess(stub("article-ids-cursor-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/ids/cursor?limit=5&direction=next"))
        .andRespond(withSuccess("{\"articleIds\":[]}", MediaType.APPLICATION_JSON));

    Assertions.assertEquals(
        Arrays.asList(A1, A2),
        client.queryIdsWithCursor(
            "java", null, null, new CursorPageParameter<>(cursor, 20, Direction.PREV)));
    Assertions.assertTrue(
        client
            .queryIdsWithCursor(
                null, null, null, new CursorPageParameter<>(null, 5, Direction.NEXT))
            .isEmpty());
    server.verify();
  }

  @Test
  public void feed_variants_send_followed_ids_and_skip_empty_author_lists() {
    server
        .expect(
            once(),
            requestTo(BASE + "/internal/articles/feed?authorIds=" + U1 + ",u2&offset=0&limit=20"))
        .andRespond(withSuccess(stub("feed-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(
            once(),
            requestTo(
                BASE
                    + "/internal/articles/feed/cursor?authorIds="
                    + U1
                    + "&limit=3&direction=next"))
        .andRespond(withSuccess(stub("feed-response.json"), MediaType.APPLICATION_JSON));

    ArticlesDto feed = client.feed(Arrays.asList(U1, "u2"), new Page(0, 20));
    Assertions.assertEquals(1, feed.getCount());
    Assertions.assertEquals(A1, feed.getArticles().get(0).getId());
    Assertions.assertEquals(
        1,
        client
            .feedWithCursor(
                Collections.singletonList(U1), new CursorPageParameter<>(null, 3, Direction.NEXT))
            .size());
    Assertions.assertEquals(0, client.feed(Collections.emptyList(), new Page(0, 20)).getCount());
    Assertions.assertTrue(
        client
            .feedWithCursor(
                Collections.emptyList(), new CursorPageParameter<>(null, 3, Direction.NEXT))
            .isEmpty());
    server.verify();
  }

  @Test
  public void create_posts_the_caller_generated_row_and_tags_with_the_callers_token() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(stub("new-article-request.json"), true))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub("article-response.json")));

    Tag java = new Tag("java");
    java.setId("t1000000-0000-0000-0000-000000000001");
    Article article =
        new Article(
            A1,
            U1,
            "java-article",
            "java article",
            "d1",
            "b1",
            Collections.singletonList(java),
            new DateTime(2024, 1, 3, 0, 0, DateTimeZone.UTC),
            new DateTime(2024, 1, 3, 0, 0, DateTimeZone.UTC));

    Assertions.assertEquals(A1, client.create(article).getId());
    server.verify();
  }

  @Test
  public void create_duplicate_slug_surfaces_the_422_without_retry() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub("article-error-duplicate-title.json")));

    ArticleServiceException e =
        Assertions.assertThrows(
            ArticleServiceException.class,
            () ->
                client.create(new Article("java article", "d", "b", Collections.emptyList(), U1)));
    Assertions.assertTrue(e.getMessage().contains("status 422"));
    server.verify();
  }

  @Test
  public void update_puts_the_mapper_semantics_body_and_delete_is_a_204() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andExpect(content().json(stub("update-article-request.json"), true))
        .andRespond(withSuccess(stub("article-response.json"), MediaType.APPLICATION_JSON));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andRespond(withNoContent());

    Article article =
        new Article(A1, U1, "old", "old", "", "", Collections.emptyList(), null, null);
    article.update("new title", "", "");
    Assertions.assertEquals(A1, client.update(article).getId());
    client.delete(A1);
    server.verify();
  }

  @Test
  public void writes_are_never_retried() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    Assertions.assertThrows(ArticleServiceException.class, () -> client.delete(A1));
    server.verify();
  }

  @Test
  public void reads_retry_once_on_connect_failure_and_503() {
    server
        .expect(times(2), requestTo(BASE + "/internal/articles/" + A1))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new ConnectException("Connection refused"));
            });
    Assertions.assertThrows(ArticleServiceException.class, () -> client.findById(A1));
    server.verify();

    server.reset();
    server
        .expect(once(), requestTo(BASE + "/internal/articles?ids=" + A1))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/articles?ids=" + A1))
        .andRespond(withSuccess(stub("articles-response.json"), MediaType.APPLICATION_JSON));
    Assertions.assertEquals(2, client.findByIds(Collections.singletonList(A1)).size());
    server.verify();
  }

  @Test
  public void read_timeout_and_5xx_are_not_retried() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + A1))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new SocketTimeoutException("Read timed out"));
            });
    Assertions.assertThrows(ArticleServiceException.class, () -> client.findById(A1));
    server.verify();

    server.reset();
    server
        .expect(once(), requestTo(BASE + "/internal/articles/ids?offset=0&limit=20"))
        .andRespond(withServerError());
    ArticleServiceException e =
        Assertions.assertThrows(
            ArticleServiceException.class,
            () -> client.queryIds(null, null, null, new Page(0, 20)));
    Assertions.assertTrue(e.getMessage().contains("status 500"));
    server.verify();
  }

  private static List<String> ids(List<ArticleRowDto> rows) {
    return rows.stream().map(ArticleRowDto::getId).collect(java.util.stream.Collectors.toList());
  }

  private void withIncomingToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  static String stub(String name) {
    try (InputStream in =
        ArticleDomainServiceClientTest.class
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
