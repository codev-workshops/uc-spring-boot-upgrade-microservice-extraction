package io.spring.infrastructure.extraction.comment;

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
import io.spring.application.comment.dto.CommentRowDto;
import io.spring.core.comment.Comment;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
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
 * Consumer-side check of {@link CommentServiceClient} against the canonical internal API; the JSON
 * fixtures under {@code src/test/resources/comment-service-stubs} mirror phase-2-comment.md section
 * 2.1.
 */
public class CommentServiceClientTest {
  private static final String BASE = "http://localhost:8082";
  private static final String ARTICLE = "a1000000-0000-0000-0000-000000000001";
  private static final String COMMENT = "c1000000-0000-0000-0000-000000000001";

  private CommentServiceClient client;
  private MockRestServiceServer server;

  @BeforeEach
  public void setUp() {
    client =
        new CommentServiceClient(
            new RestTemplateBuilder(), new ExtractionProperties(), new AuthTokenPropagator());
    server = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void list_by_article_is_read_without_credentials() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(stub("comments-response.json"), MediaType.APPLICATION_JSON));

    List<CommentRowDto> rows = client.findByArticleId(ARTICLE);

    server.verify();
    Assertions.assertEquals(2, rows.size());
    Assertions.assertEquals("second", rows.get(0).getBody());
    Assertions.assertEquals("u1000000-0000-0000-0000-000000000001", rows.get(0).getUserId());
    Assertions.assertEquals("2024-01-02T00:00:00.000Z", rows.get(0).getCreatedAt());
    Assertions.assertEquals(ARTICLE, rows.get(1).getArticleId());
  }

  @Test
  public void empty_list_is_returned_as_empty() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andRespond(withSuccess(stub("comments-empty-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertTrue(client.findByArticleId(ARTICLE).isEmpty());
    server.verify();
  }

  @Test
  public void cursor_page_sends_limit_direction_and_millis_cursor() {
    DateTime cursor = new DateTime(1704153600000L, DateTimeZone.UTC);
    server
        .expect(
            once(),
            requestTo(
                BASE
                    + "/internal/articles/"
                    + ARTICLE
                    + "/comments/cursor?limit=20&direction=next&cursor=1704153600000"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(stub("comments-response.json"), MediaType.APPLICATION_JSON));

    List<CommentRowDto> rows =
        client.findByArticleIdWithCursor(
            ARTICLE, new CursorPageParameter<>(cursor, 20, Direction.NEXT));

    server.verify();
    Assertions.assertEquals(2, rows.size());
  }

  @Test
  public void cursor_first_page_omits_the_cursor_and_uses_prev_for_last() {
    server
        .expect(
            once(),
            requestTo(
                BASE + "/internal/articles/" + ARTICLE + "/comments/cursor?limit=5&direction=prev"))
        .andRespond(withSuccess(stub("comments-empty-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertTrue(
        client
            .findByArticleIdWithCursor(ARTICLE, new CursorPageParameter<>(null, 5, Direction.PREV))
            .isEmpty());
    server.verify();
  }

  @Test
  public void find_by_id_unwraps_the_comment_envelope() {
    server
        .expect(once(), requestTo(BASE + "/internal/comments/" + COMMENT))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(stub("comment-response.json"), MediaType.APPLICATION_JSON));

    Optional<CommentRowDto> row = client.findById(COMMENT);

    server.verify();
    Assertions.assertTrue(row.isPresent());
    Assertions.assertEquals(COMMENT, row.get().getId());
    Assertions.assertEquals("first", row.get().getBody());
  }

  @Test
  public void find_by_id_maps_404_to_empty() {
    server
        .expect(once(), requestTo(BASE + "/internal/comments/missing"))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub("not-found-response.json")));

    Assertions.assertFalse(client.findById("missing").isPresent());
    server.verify();
  }

  @Test
  public void create_posts_the_monolith_generated_row_with_the_callers_token_and_is_not_retried() {
    withIncomingToken("Token jwt-1");
    Comment comment =
        new Comment(
            COMMENT,
            "first",
            "u2000000-0000-0000-0000-000000000002",
            ARTICLE,
            new DateTime(2024, 1, 1, 0, 0, DateTimeZone.UTC));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Token jwt-1"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json(stub("create-request.json"), true))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(stub("comment-response.json")));

    CommentRowDto row = client.create(comment);

    server.verify();
    Assertions.assertEquals(COMMENT, row.getId());
  }

  @Test
  public void create_failure_is_surfaced_without_retry() {
    withIncomingToken("Token jwt-1");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    Assertions.assertThrows(
        CommentServiceException.class, () -> client.create(new Comment("x", "u", ARTICLE)));
    server.verify();
  }

  @Test
  public void delete_forwards_the_callers_token_and_accepts_204() {
    withIncomingToken("Token jwt-2");
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments/" + COMMENT))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("Authorization", "Token jwt-2"))
        .andRespond(withNoContent());

    client.delete(ARTICLE, COMMENT);
    server.verify();
  }

  @Test
  public void reads_retry_once_on_connect_failure() {
    server
        .expect(times(2), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new ConnectException("Connection refused"));
            });

    Assertions.assertThrows(CommentServiceException.class, () -> client.findByArticleId(ARTICLE));
    server.verify();
  }

  @Test
  public void reads_retry_once_on_503_and_succeed() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andRespond(withSuccess(stub("comments-response.json"), MediaType.APPLICATION_JSON));

    Assertions.assertEquals(2, client.findByArticleId(ARTICLE).size());
    server.verify();
  }

  @Test
  public void read_timeout_and_5xx_are_not_retried() {
    server
        .expect(once(), requestTo(BASE + "/internal/articles/" + ARTICLE + "/comments"))
        .andRespond(
            request -> {
              throw new ResourceAccessException(
                  "I/O error", new SocketTimeoutException("Read timed out"));
            });
    Assertions.assertThrows(CommentServiceException.class, () -> client.findByArticleId(ARTICLE));
    server.verify();

    server.reset();
    server
        .expect(once(), requestTo(BASE + "/internal/comments/" + COMMENT))
        .andRespond(withServerError());
    Assertions.assertThrows(CommentServiceException.class, () -> client.findById(COMMENT));
    server.verify();
  }

  @Test
  public void iso_timestamps_are_utc_millis() {
    Assertions.assertEquals(
        "2024-01-01T00:00:00.000Z",
        CommentServiceClient.iso(new DateTime(2024, 1, 1, 0, 0, DateTimeZone.UTC)));
    Assertions.assertEquals(
        1704067200000L, RemoteCommentQueryAdapter.parse("2024-01-01T00:00:00.000Z").getMillis());
  }

  private void withIncomingToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", token);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  static String stub(String name) {
    try (InputStream in =
        CommentServiceClientTest.class
            .getClassLoader()
            .getResourceAsStream("comment-service-stubs/" + name)) {
      if (in == null) {
        throw new IllegalStateException("missing stub " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
