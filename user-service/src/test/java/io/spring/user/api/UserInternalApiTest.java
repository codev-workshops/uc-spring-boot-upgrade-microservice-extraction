package io.spring.user.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.user.api.exception.CustomizeExceptionHandler;
import io.spring.user.api.exception.InvalidRequestException;
import io.spring.user.api.exception.ResourceNotFoundException;
import io.spring.user.api.security.WebSecurityConfig;
import io.spring.user.application.UserCommandService;
import io.spring.user.application.UserQueryService;
import io.spring.user.application.data.UserData;
import io.spring.user.core.service.JwtService;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserUpdate;
import io.spring.user.infrastructure.service.DefaultJwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserInternalApi.class)
@Import({WebSecurityConfig.class, CustomizeExceptionHandler.class, DefaultJwtService.class})
public class UserInternalApiTest {
  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;
  @MockBean private UserQueryService userQueryService;
  @MockBean private UserCommandService userCommandService;

  private String token;
  private UserData row;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    token = "Token " + jwtService.toToken("user-1");
    row = new UserData("user-1", "johndoe", "john@example.com", "bio", "img");
  }

  @Test
  public void get_by_id_returns_row_without_hash_or_404_envelope() {
    when(userQueryService.findById("user-1")).thenReturn(Optional.of(row));
    when(userQueryService.findById("nope")).thenReturn(Optional.empty());
    String json =
        given().when().get("/internal/users/user-1").then().statusCode(200).extract().asString();
    Assertions.assertEquals(
        "{\"user\":{\"id\":\"user-1\",\"username\":\"johndoe\",\"email\":\"john@example.com\","
            + "\"bio\":\"bio\",\"image\":\"img\"}}",
        json);
    given()
        .when()
        .get("/internal/users/nope")
        .then()
        .statusCode(404)
        .body("errors.body[0]", equalTo("user not found"));
  }

  @Test
  public void get_by_username_and_email_return_row_or_404() {
    when(userQueryService.findByUsername("johndoe")).thenReturn(Optional.of(row));
    when(userQueryService.findByUsername("nope")).thenReturn(Optional.empty());
    when(userQueryService.findByEmail("john@example.com")).thenReturn(Optional.of(row));
    when(userQueryService.findByEmail("nope@example.com")).thenReturn(Optional.empty());
    given()
        .when()
        .get("/internal/users/by-username/johndoe")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("user-1"))
        .body("user.password", nullValue())
        .body("user.passwordHash", nullValue());
    given().when().get("/internal/users/by-username/nope").then().statusCode(404);
    given()
        .when()
        .get("/internal/users/by-email/john@example.com")
        .then()
        .statusCode(200)
        .body("user.email", equalTo("john@example.com"));
    given().when().get("/internal/users/by-email/nope@example.com").then().statusCode(404);
  }

  @Test
  public void batch_get_splits_ids_and_returns_empty_list_without_ids() {
    when(userQueryService.findByIds(Arrays.asList("user-1", "user-2")))
        .thenReturn(Arrays.asList(row));
    when(userQueryService.findByIds(Collections.emptyList())).thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/users?ids=user-1, user-2,")
        .then()
        .statusCode(200)
        .body("users", hasSize(1))
        .body("users[0].username", equalTo("johndoe"));
    given().when().get("/internal/users").then().statusCode(200).body("users", hasSize(0));
    given().when().get("/internal/users?ids=").then().statusCode(200).body("users", hasSize(0));
  }

  @Test
  public void post_creates_anonymously_with_caller_supplied_id_and_hash() {
    when(userCommandService.create(any()))
        .thenReturn(new UserCommandService.CreateResult(row, true));
    given()
        .contentType("application/json")
        .body(
            "{\"id\":\"user-1\",\"username\":\"johndoe\",\"email\":\"john@example.com\","
                + "\"passwordHash\":\"$2a$10$hash\",\"bio\":\"bio\",\"image\":\"img\"}")
        .when()
        .post("/internal/users")
        .then()
        .statusCode(201)
        .body("user.id", equalTo("user-1"))
        .body("user.password", nullValue());
    ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
    verify(userCommandService).create(user.capture());
    Assertions.assertEquals("user-1", user.getValue().getId());
    Assertions.assertEquals("$2a$10$hash", user.getValue().getPassword());
    Assertions.assertEquals("bio", user.getValue().getBio());
  }

  @Test
  public void post_returns_200_for_existing_id_and_422_for_duplicates() {
    when(userCommandService.create(any()))
        .thenReturn(new UserCommandService.CreateResult(row, false))
        .thenThrow(new InvalidRequestException("username", "duplicated username"))
        .thenThrow(new InvalidRequestException("email", "duplicated email"));
    String body =
        "{\"id\":\"user-1\",\"username\":\"johndoe\",\"email\":\"john@example.com\","
            + "\"passwordHash\":\"h\"}";
    given()
        .contentType("application/json")
        .body(body)
        .post("/internal/users")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("user-1"));
    given()
        .contentType("application/json")
        .body(body)
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.username[0]", equalTo("duplicated username"));
    given()
        .contentType("application/json")
        .body(body)
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("duplicated email"));
  }

  @Test
  public void post_rejects_missing_required_fields_and_malformed_json_with_422() {
    given()
        .contentType("application/json")
        .body("{\"id\":\"user-1\",\"username\":\"johndoe\",\"email\":\"john@example.com\"}")
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.passwordHash[0]", equalTo("can't be empty"));
    given()
        .contentType("application/json")
        .body("{not json")
        .post("/internal/users")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("invalid request body"));
    verify(userCommandService, never()).create(any());
  }

  @Test
  public void put_requires_token_matching_id_and_forwards_fields() {
    when(userCommandService.update(any())).thenReturn(row);
    given()
        .contentType("application/json")
        .body("{\"bio\":\"x\"}")
        .put("/internal/users/user-1")
        .then()
        .statusCode(401)
        .body("errors.body[0]", equalTo("missing or invalid token"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"bio\":\"x\"}")
        .put("/internal/users/user-2")
        .then()
        .statusCode(403)
        .body("errors.body[0]", equalTo("token subject does not match id"));
    verify(userCommandService, never()).update(any());

    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"username\":\"\",\"email\":null,\"passwordHash\":\"h2\",\"bio\":\"x\"}")
        .put("/internal/users/user-1")
        .then()
        .statusCode(200)
        .body("user.id", equalTo("user-1"));
    ArgumentCaptor<UserUpdate> update = ArgumentCaptor.forClass(UserUpdate.class);
    verify(userCommandService).update(update.capture());
    Assertions.assertEquals("user-1", update.getValue().getId());
    Assertions.assertEquals("", update.getValue().getUsername());
    Assertions.assertNull(update.getValue().getEmail());
    Assertions.assertEquals("h2", update.getValue().getPassword());
    Assertions.assertEquals("x", update.getValue().getBio());
  }

  @Test
  public void put_maps_not_found_and_uniqueness_clash() {
    when(userCommandService.update(any()))
        .thenThrow(new ResourceNotFoundException())
        .thenThrow(new InvalidRequestException("email", "duplicated email"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{}")
        .put("/internal/users/user-1")
        .then()
        .statusCode(404)
        .body("errors.body[0]", equalTo("user not found"));
    given()
        .header("Authorization", token)
        .contentType("application/json")
        .body("{\"email\":\"jane@example.com\"}")
        .put("/internal/users/user-1")
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("duplicated email"));
  }

  @Test
  public void verify_credentials_is_anonymous_and_returns_valid_flag() {
    when(userCommandService.verifyCredentials("user-1", "password123")).thenReturn(true);
    when(userCommandService.verifyCredentials("user-1", "wrong")).thenReturn(false);
    when(userCommandService.verifyCredentials("ghost", "password123")).thenReturn(false);
    given()
        .contentType("application/json")
        .body("{\"password\":\"password123\"}")
        .post("/internal/users/user-1/credentials/verify")
        .then()
        .statusCode(200)
        .body("valid", equalTo(true));
    given()
        .contentType("application/json")
        .body("{\"password\":\"wrong\"}")
        .post("/internal/users/user-1/credentials/verify")
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
  }

  @Test
  public void follow_reads_are_anonymous() {
    when(userQueryService.followingAuthors("user-1", Arrays.asList("user-2", "user-3")))
        .thenReturn(Arrays.asList("user-2"));
    when(userQueryService.followingAuthors("user-1", Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    when(userQueryService.followedUsers("user-1")).thenReturn(Arrays.asList("user-2", "user-3"));
    when(userQueryService.isFollowing("user-1", "user-2")).thenReturn(true);
    when(userQueryService.isFollowing("user-1", "user-3")).thenReturn(false);
    given()
        .get("/internal/users/user-1/following?ids=user-2,user-3")
        .then()
        .statusCode(200)
        .body("followingIds", contains("user-2"));
    given()
        .get("/internal/users/user-1/following")
        .then()
        .statusCode(200)
        .body("followingIds", hasSize(0));
    given()
        .get("/internal/users/user-1/followed")
        .then()
        .statusCode(200)
        .body("followedIds", contains("user-2", "user-3"));
    given()
        .get("/internal/users/user-1/follows/user-2")
        .then()
        .statusCode(200)
        .body("following", equalTo(true));
    given()
        .get("/internal/users/user-1/follows/user-3")
        .then()
        .statusCode(200)
        .body("following", equalTo(false));
  }

  @Test
  public void follow_and_unfollow_require_token_matching_id() {
    given().put("/internal/users/user-1/follows/user-2").then().statusCode(401);
    given().delete("/internal/users/user-1/follows/user-2").then().statusCode(401);
    given()
        .header("Authorization", token)
        .put("/internal/users/user-2/follows/user-1")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", token)
        .delete("/internal/users/user-2/follows/user-1")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", "Token not-a-jwt")
        .put("/internal/users/user-1/follows/user-2")
        .then()
        .statusCode(401);
    verify(userCommandService, never()).follow(any(), any());
    verify(userCommandService, never()).unfollow(any(), any());

    given()
        .header("Authorization", token)
        .put("/internal/users/user-1/follows/user-2")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", token)
        .delete("/internal/users/user-1/follows/user-2")
        .then()
        .statusCode(204);
    verify(userCommandService).follow("user-1", "user-2");
    verify(userCommandService).unfollow("user-1", "user-2");
  }
}
