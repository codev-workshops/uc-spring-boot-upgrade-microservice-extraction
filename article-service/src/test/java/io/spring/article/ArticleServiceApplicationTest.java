package io.spring.article;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.article.core.service.JwtService;
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
public class ArticleServiceApplicationTest {
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
  public void tag_round_trip() {
    String token = "Token " + jwtService.toToken("user-1");
    given().get("/internal/tags").then().statusCode(200).body("tags", hasSize(0));
    given()
        .contentType("application/json")
        .body("{\"tags\":[{\"id\":\"t-1\",\"name\":\"java\"}]}")
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(401);
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"tags\":[{\"id\":\"t-1\",\"name\":\"java\"},{\"id\":\"t-2\",\"name\":\"sql\"}]}")
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(200)
        .body("articleId", equalTo("a"))
        .body("tagList", hasSize(2))
        .body("tagList[0]", equalTo("java"))
        .body("tagList[1]", equalTo("sql"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"tags\":[{\"id\":\"t-9\",\"name\":\"java\"}]}")
        .put("/internal/articles/b/tags")
        .then()
        .statusCode(200)
        .body("tagList", hasSize(1));
    given()
        .get("/internal/tags")
        .then()
        .statusCode(200)
        .body("tags", hasSize(2))
        .body("tags[0]", equalTo("java"))
        .body("tags[1]", equalTo("sql"));
    given()
        .get("/internal/articles/tags?articleIds=a,b,c")
        .then()
        .statusCode(200)
        .body("articleTags", hasSize(3))
        .body("articleTags[0].articleId", equalTo("a"))
        .body("articleTags[0].tagList", hasSize(2))
        .body("articleTags[1].articleId", equalTo("b"))
        .body("articleTags[1].tagList[0]", equalTo("java"))
        .body("articleTags[2].articleId", equalTo("c"))
        .body("articleTags[2].tagList", hasSize(0));
    given()
        .get("/internal/tags/java/article-ids")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(2))
        .body("articleIds[0]", equalTo("a"))
        .body("articleIds[1]", equalTo("b"));
    given()
        .get("/internal/tags/unknown/article-ids")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(0));
  }
}
