package io.spring.user;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.user.core.service.JwtService;
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
public class UserServiceApplicationTest {
  /** BCrypt of "password123" (the monolith seed hash). */
  private static final String HASH = "$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u";

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
  public void user_round_trip() {
    String token = "Token " + jwtService.toToken("rt-1");
    String create =
        "{\"id\":\"rt-1\",\"username\":\"rt\",\"email\":\"rt@example.com\",\"passwordHash\":\""
            + HASH
            + "\",\"bio\":\"\",\"image\":\"img\"}";
    given()
        .contentType("application/json")
        .body(create)
        .post("/internal/users")
        .then()
        .statusCode(201)
        .body("user.id", equalTo("rt-1"))
        .body("user.username", equalTo("rt"))
        .body("user.password", nullValue());
    given()
        .contentType("application/json")
        .body(create)
        .post("/internal/users")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("rt-1"));
    given()
        .contentType("application/json")
        .body(
            "{\"id\":\"rt-2\",\"username\":\"rt\",\"email\":\"rt2@example.com\","
                + "\"passwordHash\":\"h\"}")
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.username[0]", equalTo("duplicated username"));
    given()
        .contentType("application/json")
        .body(
            "{\"id\":\"rt-2\",\"username\":\"rt2\",\"email\":\"rt@example.com\","
                + "\"passwordHash\":\"h\"}")
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("duplicated email"));
    given()
        .contentType("application/json")
        .body(
            "{\"id\":\"rt-2\",\"username\":\"rt2\",\"email\":\"rt2@example.com\","
                + "\"passwordHash\":\"h\"}")
        .post("/internal/users")
        .then()
        .statusCode(201);

    given()
        .get("/internal/users/rt-1")
        .then()
        .statusCode(200)
        .body("user.email", equalTo("rt@example.com"));
    given()
        .get("/internal/users/by-username/rt")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("rt-1"));
    given()
        .get("/internal/users/by-email/rt@example.com")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("rt-1"));
    given().get("/internal/users/by-email/none@example.com").then().statusCode(404);
    given()
        .get("/internal/users?ids=rt-1,rt-2,ghost")
        .then()
        .statusCode(200)
        .body("users", hasSize(2));
    given().get("/internal/users").then().statusCode(200).body("users", hasSize(0));

    given()
        .contentType("application/json")
        .body("{\"password\":\"password123\"}")
        .post("/internal/users/rt-1/credentials/verify")
        .then()
        .statusCode(200)
        .body("valid", equalTo(true));
    given()
        .contentType("application/json")
        .body("{\"password\":\"nope\"}")
        .post("/internal/users/rt-1/credentials/verify")
        .then()
        .statusCode(200)
        .body("valid", equalTo(false));
    given()
        .contentType("application/json")
        .body("{\"password\":\"password123\"}")
        .post("/internal/users/ghost/credentials/verify")
        .then()
        .statusCode(200)
        .body("valid", equalTo(false));

    given()
        .contentType("application/json")
        .body("{\"bio\":\"b\"}")
        .put("/internal/users/rt-1")
        .then()
        .statusCode(401);
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"bio\":\"b\"}")
        .put("/internal/users/rt-2")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"username\":\"\",\"bio\":\"new bio\",\"passwordHash\":\"\"}")
        .put("/internal/users/rt-1")
        .then()
        .statusCode(200)
        .body("user.username", equalTo("rt"))
        .body("user.bio", equalTo("new bio"))
        .body("user.image", equalTo("img"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"email\":\"rt2@example.com\"}")
        .put("/internal/users/rt-1")
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("duplicated email"));
    given()
        .contentType("application/json")
        .body("{\"password\":\"password123\"}")
        .post("/internal/users/rt-1/credentials/verify")
        .then()
        .body("valid", equalTo(true));

    given()
        .get("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(200)
        .body("following", equalTo(false));
    given().put("/internal/users/rt-1/follows/rt-2").then().statusCode(401);
    given()
        .header("Authorization", token)
        .put("/internal/users/rt-2/follows/rt-1")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", token)
        .put("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .put("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .put("/internal/users/rt-1/follows/rt-3")
        .then()
        .statusCode(204);
    given()
        .get("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(200)
        .body("following", equalTo(true));
    given()
        .get("/internal/users/rt-1/followed")
        .then()
        .statusCode(200)
        .body("followedIds", contains("rt-2", "rt-3"));
    given()
        .get("/internal/users/rt-1/following?ids=rt-2,rt-9")
        .then()
        .statusCode(200)
        .body("followingIds", contains("rt-2"));
    given()
        .header("Authorization", token)
        .delete("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .delete("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(204);
    given()
        .get("/internal/users/rt-1/follows/rt-2")
        .then()
        .statusCode(200)
        .body("following", equalTo(false));
    given()
        .get("/internal/users/rt-1/followed")
        .then()
        .statusCode(200)
        .body("followedIds", contains("rt-3"));
  }
}
