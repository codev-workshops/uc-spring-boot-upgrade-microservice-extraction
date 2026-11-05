package io.spring.infrastructure.comments;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentServiceException;
import io.spring.infrastructure.repository.HttpCommentRepository;
import java.time.Duration;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

public class CommentServiceClientTest {
  private static final String BASE_URL = "http://comments-service";
  private static final String COMMENT_JSON =
      "{\"id\":\"c1\",\"body\":\"content\",\"articleId\":\"a1\",\"userId\":\"u1\","
          + "\"createdAt\":\"2021-01-01T00:00:00.000Z\",\"updatedAt\":\"2021-01-01T00:00:00.000Z\"}";

  private MockRestServiceServer server;
  private CommentServiceClient client;
  private HttpCommentRepository repository;

  @BeforeEach
  public void setUp() {
    MockServerRestTemplateCustomizer customizer = new MockServerRestTemplateCustomizer();
    client =
        new CommentServiceClient(
            new RestTemplateBuilder(customizer),
            BASE_URL,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            "test-token");
    server = customizer.getServer();
    repository = new HttpCommentRepository(client);
  }

  @Test
  public void should_create_comment_with_monolith_generated_id() {
    Comment comment = new Comment("content", "u1", "a1");
    server
        .expect(requestTo("/articles/a1/comments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Service-Token", "test-token"))
        .andExpect(
            content()
                .json(
                    "{\"comment\":{\"id\":\""
                        + comment.getId()
                        + "\",\"body\":\"content\",\"userId\":\"u1\"}}"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"comment\":" + COMMENT_JSON + "}"));

    Comment saved = repository.save(comment);
    server.verify();
    Assertions.assertEquals("c1", saved.getId());
    Assertions.assertEquals("content", saved.getBody());
    Assertions.assertEquals(
        new DateTime("2021-01-01T00:00:00.000Z").getMillis(), saved.getCreatedAt().getMillis());
  }

  @Test
  public void should_throw_domain_exception_when_create_fails() {
    server.expect(requestTo("/articles/a1/comments")).andRespond(withServerError());

    CommentServiceException e =
        Assertions.assertThrows(
            CommentServiceException.class, () -> client.create("a1", "c1", "u1", "content"));
    Assertions.assertTrue(e.getMessage().contains("comments-service"));
  }

  @Test
  public void should_return_empty_when_comment_not_found() {
    server
        .expect(requestTo("/articles/a1/comments/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    Assertions.assertFalse(repository.findById("a1", "missing").isPresent());
  }

  @Test
  public void should_map_remote_comment_to_domain_comment() {
    server
        .expect(requestTo("/articles/a1/comments/c1"))
        .andRespond(withSuccess("{\"comment\":" + COMMENT_JSON + "}", MediaType.APPLICATION_JSON));

    Comment comment = repository.findById("a1", "c1").get();
    Assertions.assertEquals("c1", comment.getId());
    Assertions.assertEquals("u1", comment.getUserId());
    Assertions.assertEquals("a1", comment.getArticleId());
  }

  @Test
  public void should_throw_domain_exception_when_lookup_fails() {
    server.expect(requestTo("/articles/a1/comments/c1")).andRespond(withServerError());

    Assertions.assertThrows(
        CommentServiceException.class, () -> client.findByArticleIdAndId("a1", "c1"));
  }

  @Test
  public void should_list_comments_of_article() {
    server
        .expect(requestTo("/articles/a1/comments"))
        .andRespond(
            withSuccess("{\"comments\":[" + COMMENT_JSON + "]}", MediaType.APPLICATION_JSON));

    List<RemoteComment> comments = client.findByArticleId("a1");
    Assertions.assertEquals(1, comments.size());
  }

  @Test
  public void should_return_empty_list_when_service_is_down() {
    server.expect(requestTo("/articles/a1/comments")).andRespond(withServerError());

    Assertions.assertTrue(client.findByArticleId("a1").isEmpty());
  }

  @Test
  public void should_pass_cursor_parameters() {
    server
        .expect(requestTo("/articles/a1/comments?limit=5&direction=PREV&cursor=1000"))
        .andRespond(withSuccess("{\"comments\":[]}", MediaType.APPLICATION_JSON));

    List<RemoteComment> comments =
        client.findByArticleIdWithCursor(
            "a1", new CursorPageParameter<>(new DateTime(1000L), 5, Direction.PREV));
    Assertions.assertTrue(comments.isEmpty());
    server.verify();
  }

  @Test
  public void should_delete_comment() {
    server
        .expect(requestTo("/articles/a1/comments/c1"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withNoContent());

    repository.remove(new Comment("c1", "content", "u1", "a1", new DateTime()));
    server.verify();
  }

  @Test
  public void should_ignore_delete_of_missing_comment() {
    server
        .expect(requestTo("/articles/a1/comments/c1"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    client.delete("a1", "c1");
    server.verify();
  }

  @Test
  public void should_throw_domain_exception_when_delete_fails() {
    server.expect(requestTo("/articles/a1/comments/c1")).andRespond(withServerError());

    Assertions.assertThrows(CommentServiceException.class, () -> client.delete("a1", "c1"));
  }
}
