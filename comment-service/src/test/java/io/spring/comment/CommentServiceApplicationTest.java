package io.spring.comment;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.comment.core.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full context against the in-memory test database (Flyway V1 only). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class CommentServiceApplicationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
  }

  @Test
  public void health_is_up() {
    given().when().get("/actuator/health").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  public void comment_round_trip_with_monolith_timestamp_format() {
    String token = "Token " + jwtService.toToken("user-1");
    String body =
        "{\"id\":\"c-rt\",\"body\":\"hi\",\"userId\":\"user-1\","
            + "\"createdAt\":\"2024-01-31T10:15:30.123Z\"}";
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body(body)
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(201)
        .body("comment.id", equalTo("c-rt"))
        .body("comment.body", equalTo("hi"))
        .body("comment.articleId", equalTo("a"))
        .body("comment.userId", equalTo("user-1"))
        .body("comment.createdAt", equalTo("2024-01-31T10:15:30.123Z"))
        .body("comment.updatedAt", equalTo("2024-01-31T10:15:30.123Z"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body(body)
        .post("/internal/articles/a/comments")
        .then()
        .statusCode(200)
        .body("comment.id", equalTo("c-rt"));
    given()
        .get("/internal/articles/a/comments")
        .then()
        .statusCode(200)
        .body("comments", hasSize(1))
        .body("comments[0].createdAt", equalTo("2024-01-31T10:15:30.123Z"));
    given()
        .get("/internal/articles/a/comments/cursor?limit=1&direction=next")
        .then()
        .statusCode(200)
        .body("comments", hasSize(1));
    given()
        .get("/internal/articles/a/comments/cursor?limit=1&direction=next&cursor=1706696130123")
        .then()
        .statusCode(200)
        .body("comments", hasSize(0));
    given()
        .get("/internal/comments/c-rt")
        .then()
        .statusCode(200)
        .body("comment.id", equalTo("c-rt"));
    given()
        .get("/internal/comments/c-rt?articleId=other")
        .then()
        .statusCode(404)
        .body("errors.body[0]", equalTo("comment not found"));
    given().delete("/internal/articles/a/comments/c-rt").then().statusCode(401);
    given()
        .header("Authorization", token)
        .delete("/internal/articles/a/comments/c-rt")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .delete("/internal/articles/a/comments/c-rt")
        .then()
        .statusCode(204);
    given().get("/internal/comments/c-rt").then().statusCode(404);
  }
}
