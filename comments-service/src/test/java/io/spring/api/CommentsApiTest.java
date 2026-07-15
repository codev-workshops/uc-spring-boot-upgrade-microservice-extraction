package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.TestHelper;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.CommentQueryService;
import io.spring.application.data.CommentData;
import io.spring.client.ArticleServiceClient;
import io.spring.client.dto.ArticleResponse;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommentsApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class CommentsApiTest extends TestWithCurrentUser {
  @MockBean private ArticleServiceClient articleServiceClient;
  @MockBean private CommentRepository commentRepository;
  @MockBean private CommentQueryService commentQueryService;

  @Autowired private MockMvc mvc;

  private ArticleResponse article;
  private Comment comment;
  private CommentData commentData;

  @BeforeEach
  public void configure() {
    RestAssuredMockMvc.mockMvc(mvc);
    article = new ArticleResponse("article-1", userId);
    comment = new Comment("comment", userId, article.getId());
    commentData = TestHelper.commentDataFixture(comment);
    when(articleServiceClient.findBySlug("article-slug")).thenReturn(Optional.of(article));
    when(articleServiceClient.findBySlugForRead("article-slug")).thenReturn(Optional.of(article));
  }

  @Test
  public void shouldCreateComment() {
    Map<String, Object> param = new HashMap<>();
    param.put("comment", Collections.singletonMap("body", "comment content"));
    when(commentQueryService.findById(anyString(), eq(userId)))
        .thenReturn(Optional.of(commentData));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .post("/articles/article-slug/comments")
        .then()
        .statusCode(201)
        .body("comment.body", equalTo(commentData.getBody()));
  }

  @Test
  public void shouldRejectEmptyBody() {
    Map<String, Object> param = new HashMap<>();
    param.put("comment", Collections.singletonMap("body", ""));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .post("/articles/article-slug/comments")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("can't be empty"));
  }

  @Test
  public void shouldGetCommentsWithoutAuthentication() {
    when(commentQueryService.findByArticleId(article.getId(), null))
        .thenReturn(Collections.singletonList(commentData));

    given()
        .when()
        .get("/articles/article-slug/comments")
        .then()
        .statusCode(200)
        .body("comments[0].id", equalTo(commentData.getId()));
  }

  @Test
  public void shouldDeleteComment() {
    when(commentRepository.findById(article.getId(), comment.getId()))
        .thenReturn(Optional.of(comment));

    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/article-slug/comments/{id}", comment.getId())
        .then()
        .statusCode(204);
  }

  @Test
  public void shouldForbidDeleteByUnrelatedUser() {
    String unrelatedUserId = "user-3";
    when(jwtService.getSubFromToken("other-token")).thenReturn(Optional.of(unrelatedUserId));
    when(commentRepository.findById(article.getId(), comment.getId()))
        .thenReturn(Optional.of(comment));

    given()
        .header("Authorization", "Token other-token")
        .when()
        .delete("/articles/article-slug/comments/{id}", comment.getId())
        .then()
        .statusCode(403);
  }
}
