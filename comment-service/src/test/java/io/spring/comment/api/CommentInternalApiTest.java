package io.spring.comment.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.comment.JacksonCustomizations;
import io.spring.comment.api.exception.CustomizeExceptionHandler;
import io.spring.comment.api.security.WebSecurityConfig;
import io.spring.comment.application.CommentCommandService;
import io.spring.comment.application.CommentQueryService;
import io.spring.comment.application.CursorPageParameter;
import io.spring.comment.application.CursorPageParameter.Direction;
import io.spring.comment.application.data.CommentData;
import io.spring.comment.core.service.JwtService;
import io.spring.comment.infrastructure.service.DefaultJwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommentInternalApi.class)
@Import({
  WebSecurityConfig.class,
  CustomizeExceptionHandler.class,
  DefaultJwtService.class,
  JacksonCustomizations.class
})
public class CommentInternalApiTest {
  private static final DateTime T1 = new DateTime(1706696130123L, DateTimeZone.UTC);
  private static final String T1_ISO = "2024-01-31T10:15:30.123Z";

  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;
  @MockBean private CommentQueryService commentQueryService;
  @MockBean private CommentCommandService commentCommandService;

  private String token;
  private CommentData row;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    token = jwtService.toToken("user-1");
    row = new CommentData("c-1", "hello", "a", "user-1", T1, T1);
  }

  private static CommentCommandService.CreateResult result(CommentData data, boolean created) {
    return new CommentCommandService.CreateResult(data, created);
  }

  @Test
  public void list_returns_rows_with_monolith_field_order_and_timestamps() {
    when(commentQueryService.findByArticleId("a")).thenReturn(Arrays.asList(row));
    String json =
        given()
            .when()
            .get("/internal/articles/a/comments")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"comments\":[{\"id\":\"c-1\",\"body\":\"hello\",\"articleId\":\"a\",\"userId\":\"user-1\","
            + "\"createdAt\":\""
            + T1_ISO
            + "\",\"updatedAt\":\""
            + T1_ISO
            + "\"}]}",
        json);
  }

  @Test
  public void list_is_empty_for_unknown_article() {
    when(commentQueryService.findByArticleId("none")).thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/articles/none/comments")
        .then()
        .statusCode(200)
        .body("comments", hasSize(0));
  }

  @Test
  public void cursor_list_passes_millis_cursor_limit_and_direction() {
    when(commentQueryService.findByArticleIdWithCursor(eq("a"), any()))
        .thenReturn(Arrays.asList(row));
    given()
        .when()
        .get("/internal/articles/a/comments/cursor?limit=5&direction=prev&cursor=1706696130123")
        .then()
        .statusCode(200)
        .body("comments", hasSize(1))
        .body("comments[0].createdAt", equalTo(T1_ISO));
    ArgumentCaptor<CursorPageParameter> page = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService).findByArticleIdWithCursor(eq("a"), page.capture());
    org.junit.jupiter.api.Assertions.assertEquals(5, page.getValue().getLimit());
    org.junit.jupiter.api.Assertions.assertEquals(6, page.getValue().getQueryLimit());
    org.junit.jupiter.api.Assertions.assertEquals(Direction.PREV, page.getValue().getDirection());
    org.junit.jupiter.api.Assertions.assertEquals(
        1706696130123L, page.getValue().getCursor().getMillis());
  }

  @Test
  public void cursor_list_defaults_to_next_limit_20_without_cursor() {
    when(commentQueryService.findByArticleIdWithCursor(eq("a"), any()))
        .thenReturn(Collections.emptyList());
    given().when().get("/internal/articles/a/comments/cursor").then().statusCode(200);
    ArgumentCaptor<CursorPageParameter> page = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService).findByArticleIdWithCursor(eq("a"), page.capture());
    org.junit.jupiter.api.Assertions.assertEquals(20, page.getValue().getLimit());
    org.junit.jupiter.api.Assertions.assertTrue(page.getValue().isNext());
    org.junit.jupiter.api.Assertions.assertNull(page.getValue().getCursor());
  }

  @Test
  public void cursor_list_rejects_bad_direction_or_cursor_with_422() {
    given()
        .when()
        .get("/internal/articles/a/comments/cursor?direction=sideways")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("direction must be next or prev"));
    given()
        .when()
        .get("/internal/articles/a/comments/cursor?cursor=yesterday")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("cursor must be epoch millis"));
  }

  @Test
  public void get_returns_comment_or_404_envelope() {
    when(commentQueryService.findById("c-1")).thenReturn(Optional.of(row));
    when(commentQueryService.findById("nope")).thenReturn(Optional.empty());
    given()
        .when()
        .get("/internal/comments/c-1")
        .then()
        .statusCode(200)
        .body("comment.id", equalTo("c-1"))
        .body("comment.userId", equalTo("user-1"));
    given()
        .when()
        .get("/internal/comments/nope")
        .then()
        .statusCode(404)
        .body("errors.body[0]", equalTo("comment not found"));
    given().when().get("/internal/comments/c-1?articleId=a").then().statusCode(200);
    given().when().get("/internal/comments/c-1?articleId=b").then().statusCode(404);
  }

  @Test
  public void post_creates_with_caller_supplied_id_and_created_at() {
    when(commentCommandService.create("a", "c-1", "hello", "user-1", T1))
        .thenReturn(result(row, true));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body(
            "{\"id\":\"c-1\",\"body\":\"hello\",\"userId\":\"user-1\",\"createdAt\":\""
                + T1_ISO
                + "\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(201)
        .body("comment.id", equalTo("c-1"))
        .body("comment.createdAt", equalTo(T1_ISO))
        .body("comment.updatedAt", equalTo(T1_ISO));
  }

  @Test
  public void post_accepts_epoch_millis_created_at() {
    when(commentCommandService.create("a", "c-1", "hello", "user-1", T1))
        .thenReturn(result(row, true));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body(
            "{\"id\":\"c-1\",\"body\":\"hello\",\"userId\":\"user-1\",\"createdAt\":1706696130123}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(201);
  }

  @Test
  public void post_duplicate_id_is_200_with_same_row() {
    when(commentCommandService.create(eq("a"), eq("c-1"), anyString(), eq("user-1"), any()))
        .thenReturn(result(row, false));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"id\":\"c-1\",\"body\":\"hello\",\"userId\":\"user-1\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(200)
        .body("comment.id", equalTo("c-1"));
  }

  @Test
  public void post_blank_body_is_422_with_monolith_message() {
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"body\":\"   \",\"userId\":\"user-1\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("body can't be empty"));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("not json")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("invalid request body"));
    verify(commentCommandService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  public void post_without_or_with_invalid_token_is_401() {
    given()
        .contentType("application/json")
        .body("{\"body\":\"hello\",\"userId\":\"user-1\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(401)
        .body("errors.body[0]", equalTo("missing or invalid token"));
    given()
        .header("Authorization", "Token not-a-jwt")
        .contentType("application/json")
        .body("{\"body\":\"hello\",\"userId\":\"user-1\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(401);
    given()
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .body("{\"body\":\"hello\",\"userId\":\"user-1\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(401);
    verify(commentCommandService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  public void post_for_other_user_is_403() {
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"body\":\"hello\",\"userId\":\"user-2\"}")
        .when()
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(403)
        .body("errors.body[0]", equalTo("token subject does not match userId"));
    verify(commentCommandService, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  public void delete_is_204_for_any_authenticated_user_and_401_without_token() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/internal/articles/a/comments/c-1")
        .then()
        .statusCode(204);
    verify(commentCommandService).delete("a", "c-1");
    given().when().delete("/internal/articles/a/comments/c-1").then().statusCode(401);
    verify(commentCommandService, never()).delete("a", "c-2");
  }
}
