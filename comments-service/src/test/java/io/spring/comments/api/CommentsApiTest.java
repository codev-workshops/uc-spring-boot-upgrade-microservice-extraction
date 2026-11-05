package io.spring.comments.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.comments.JacksonCustomizations;
import io.spring.comments.api.security.ServiceTokenFilter;
import io.spring.comments.application.CommentQueryService;
import io.spring.comments.application.CursorPageParameter;
import io.spring.comments.application.Direction;
import io.spring.comments.application.data.CommentData;
import io.spring.comments.core.comment.Comment;
import io.spring.comments.core.comment.CommentRepository;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommentsApi.class)
@Import(JacksonCustomizations.class)
@TestPropertySource(properties = "comments.service.token=test-token")
public class CommentsApiTest {
  @MockBean private CommentRepository commentRepository;
  @MockBean private CommentQueryService commentQueryService;
  @Autowired private MockMvc mvc;

  private Comment comment;
  private CommentData commentData;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    comment = new Comment("comment", "user-1", "article-1");
    commentData =
        new CommentData(
            comment.getId(),
            comment.getBody(),
            comment.getArticleId(),
            comment.getUserId(),
            comment.getCreatedAt(),
            comment.getCreatedAt());
  }

  private Map<String, Object> newCommentParam(String body, String userId) {
    Map<String, Object> comment = new HashMap<>();
    comment.put("body", body);
    comment.put("userId", userId);
    Map<String, Object> param = new HashMap<>();
    param.put("comment", comment);
    return param;
  }

  @Test
  public void should_create_comment_success() {
    when(commentQueryService.findById(anyString())).thenReturn(Optional.of(commentData));

    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .contentType("application/json")
        .body(newCommentParam("comment content", "user-1"))
        .when()
        .post("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(201)
        .body("comment.body", equalTo(commentData.getBody()))
        .body("comment.userId", equalTo("user-1"));

    ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
    verify(commentRepository).save(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("article-1", captor.getValue().getArticleId());
    org.junit.jupiter.api.Assertions.assertEquals("comment content", captor.getValue().getBody());
  }

  @Test
  public void should_create_comment_with_client_supplied_id() {
    when(commentQueryService.findById(eq("client-id"))).thenReturn(Optional.of(commentData));
    Map<String, Object> param = newCommentParam("comment content", "user-1");
    ((Map<String, Object>) param.get("comment")).put("id", "client-id");

    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .contentType("application/json")
        .body(param)
        .when()
        .post("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(201);

    ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
    verify(commentRepository).save(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("client-id", captor.getValue().getId());
  }

  @Test
  public void should_get_422_with_empty_body() {
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .contentType("application/json")
        .body(newCommentParam("", "user-1"))
        .when()
        .post("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("can't be empty"));
    verify(commentRepository, never()).save(any());
  }

  @Test
  public void should_get_comments_of_article_success() {
    when(commentQueryService.findByArticleId(eq("article-1")))
        .thenReturn(Arrays.asList(commentData));
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .get("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(200)
        .body("comments[0].id", equalTo(commentData.getId()))
        .body("comments[0].createdAt", org.hamcrest.Matchers.notNullValue());
  }

  @Test
  public void should_get_comments_with_cursor_success() {
    when(commentQueryService.findByArticleIdWithCursor(eq("article-1"), any()))
        .thenReturn(Arrays.asList(commentData));
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .queryParam("limit", 5)
        .queryParam("cursor", 1000L)
        .queryParam("direction", "PREV")
        .when()
        .get("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(200)
        .body("comments[0].id", equalTo(commentData.getId()));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService).findByArticleIdWithCursor(eq("article-1"), captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(5, captor.getValue().getLimit());
    org.junit.jupiter.api.Assertions.assertEquals(Direction.PREV, captor.getValue().getDirection());
    org.junit.jupiter.api.Assertions.assertEquals(1000L, captor.getValue().getCursor().getMillis());
  }

  @Test
  public void should_get_comment_by_id() {
    when(commentQueryService.findById(eq(comment.getId()))).thenReturn(Optional.of(commentData));
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .get("/comments/{id}", comment.getId())
        .then()
        .statusCode(200)
        .body("comment.id", equalTo(comment.getId()));
  }

  @Test
  public void should_get_404_for_unknown_comment() {
    when(commentQueryService.findById(anyString())).thenReturn(Optional.empty());
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .get("/comments/{id}", "missing")
        .then()
        .statusCode(404);
  }

  @Test
  public void should_get_article_comment() {
    when(commentRepository.findById(eq("article-1"), eq(comment.getId())))
        .thenReturn(Optional.of(comment));
    when(commentQueryService.findById(eq(comment.getId()))).thenReturn(Optional.of(commentData));
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .get("/articles/{articleId}/comments/{id}", "article-1", comment.getId())
        .then()
        .statusCode(200)
        .body("comment.userId", equalTo("user-1"));
  }

  @Test
  public void should_delete_comment_success() {
    when(commentRepository.findById(eq("article-1"), eq(comment.getId())))
        .thenReturn(Optional.of(comment));
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .delete("/articles/{articleId}/comments/{id}", "article-1", comment.getId())
        .then()
        .statusCode(204);
    verify(commentRepository).remove(eq(comment));
  }

  @Test
  public void should_get_404_when_deleting_unknown_comment() {
    when(commentRepository.findById(anyString(), anyString())).thenReturn(Optional.empty());
    given()
        .header(ServiceTokenFilter.HEADER, "test-token")
        .when()
        .delete("/articles/{articleId}/comments/{id}", "article-1", "missing")
        .then()
        .statusCode(404);
    verify(commentRepository, never()).remove(any());
  }

  @Test
  public void should_reject_request_without_service_token() {
    given()
        .contentType("application/json")
        .body(newCommentParam("forged", "someone-else"))
        .when()
        .post("/articles/{articleId}/comments", "article-1")
        .then()
        .statusCode(401);
    verify(commentRepository, never()).save(any());
  }

  @Test
  public void should_reject_request_with_wrong_service_token() {
    given()
        .header(ServiceTokenFilter.HEADER, "wrong-token")
        .when()
        .delete("/articles/{articleId}/comments/{id}", "article-1", comment.getId())
        .then()
        .statusCode(401);
    verify(commentRepository, never()).remove(any());
  }
}
