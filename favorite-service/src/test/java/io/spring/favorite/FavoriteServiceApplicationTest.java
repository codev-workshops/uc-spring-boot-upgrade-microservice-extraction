package io.spring.favorite;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.favorite.core.service.JwtService;
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
public class FavoriteServiceApplicationTest {
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
  public void favorite_round_trip() {
    String token = "Token " + jwtService.toToken("user-1");
    given()
        .header("Authorization", token)
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(200);
    given()
        .header("Authorization", token)
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"articleIds\":[\"a\",\"b\"]}")
        .post("/internal/favorites/counts")
        .then()
        .statusCode(200)
        .body("counts[0].count", equalTo(1))
        .body("counts[1].count", equalTo(0));
    given()
        .contentType("application/json")
        .body("{\"userId\":\"user-1\",\"articleIds\":[\"a\",\"b\"]}")
        .post("/internal/favorites/query")
        .then()
        .statusCode(200)
        .body("articleIds[0]", equalTo("a"));
    given()
        .get("/internal/favorites/by-user/user-1/article-ids")
        .then()
        .statusCode(200)
        .body("articleIds[0]", equalTo("a"));
    given()
        .header("Authorization", token)
        .delete("/internal/favorites/a/user-1")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .delete("/internal/favorites/a/user-1")
        .then()
        .statusCode(204);
    given()
        .contentType("application/json")
        .body("{\"articleIds\":[\"a\"]}")
        .post("/internal/favorites/counts")
        .then()
        .body("counts[0].count", equalTo(0));
  }
}
