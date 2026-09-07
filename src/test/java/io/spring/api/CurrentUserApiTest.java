package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.UserQueryService;
import io.spring.application.user.UserService;
import io.spring.core.user.User;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CurrentUserApi.class)
@Import({
  WebSecurityConfig.class,
  JacksonCustomizations.class,
  UserService.class,
  ValidationAutoConfiguration.class,
  BCryptPasswordEncoder.class
})
public class CurrentUserApiTest extends TestWithCurrentUser {

  @Autowired private MockMvc mvc;

  @MockBean private UserQueryService userQueryService;

  @Override
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
  }

  @Test
  public void should_get_current_user_with_token() throws Exception {
    when(userQueryService.findById(any())).thenReturn(Optional.of(userData));

    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .when()
        .get("/user")
        .then()
        .statusCode(200)
        .body("user.email", equalTo(email))
        .body("user.username", equalTo(username))
        .body("user.bio", equalTo(""))
        .body("user.image", equalTo(defaultAvatar))
        .body("user.token", equalTo(token));
  }

  @Test
  public void should_get_401_without_token() throws Exception {
    given().contentType("application/json").when().get("/user").then().statusCode(401);
  }

  @Test
  public void should_get_401_with_invalid_token() throws Exception {
    String invalidToken = "asdfasd";
    when(jwtService.getSubFromToken(eq(invalidToken))).thenReturn(Optional.empty());
    given()
        .contentType("application/json")
        .header("Authorization", "Token " + invalidToken)
        .when()
        .get("/user")
        .then()
        .statusCode(401);
  }

  @Test
  public void should_update_current_user_profile() throws Exception {
    String newEmail = "newemail@example.com";
    String newBio = "updated";
    String newUsername = "newusernamee";

    Map<String, Object> param =
        new HashMap<String, Object>() {
          {
            put(
                "user",
                new HashMap<String, Object>() {
                  {
                    put("email", newEmail);
                    put("bio", newBio);
                    put("username", newUsername);
                  }
                });
          }
        };

    when(userRepository.findByUsername(eq(newUsername))).thenReturn(Optional.empty());
    when(userRepository.findByEmail(eq(newEmail))).thenReturn(Optional.empty());

    when(userQueryService.findById(eq(user.getId()))).thenReturn(Optional.of(userData));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .put("/user")
        .then()
        .statusCode(200)
        .body("user.email", equalTo(email))
        .body("user.token", equalTo(token));

    verify(userRepository).save(user);
    assertEquals(newEmail, user.getEmail());
    assertEquals(newUsername, user.getUsername());
    assertEquals(newBio, user.getBio());
  }

  @Test
  public void should_allow_update_with_own_email_and_username() throws Exception {
    Map<String, Object> param = prepareUpdateParam(email, "new bio", username);

    when(userRepository.findByEmail(eq(email))).thenReturn(Optional.of(user));
    when(userQueryService.findById(eq(user.getId()))).thenReturn(Optional.of(userData));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .put("/user")
        .then()
        .statusCode(200);

    verify(userRepository).save(user);
    assertEquals("new bio", user.getBio());
  }

  @Test
  public void should_get_error_if_username_exists_when_update_user_profile() throws Exception {
    String newEmail = "newemail@example.com";
    String newUsername = "taken";

    Map<String, Object> param = prepareUpdateParam(newEmail, "bio", newUsername);

    when(userRepository.findByEmail(eq(newEmail))).thenReturn(Optional.empty());
    when(userRepository.findByUsername(eq(newUsername)))
        .thenReturn(Optional.of(new User("other@example.com", newUsername, "123", "", "")));
    when(userQueryService.findById(eq(user.getId()))).thenReturn(Optional.of(userData));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .put("/user")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("errors.username[0]", equalTo("username already exist"))
        .body("errors.email", nullValue());

    verify(userRepository, never()).save(any());
  }

  @Test
  public void should_get_error_if_email_exists_when_update_user_profile() throws Exception {
    String newEmail = "newemail@example.com";
    String newBio = "updated";
    String newUsername = "newusernamee";

    Map<String, Object> param = prepareUpdateParam(newEmail, newBio, newUsername);

    when(userRepository.findByEmail(eq(newEmail)))
        .thenReturn(Optional.of(new User(newEmail, "username", "123", "", "")));
    when(userRepository.findByUsername(eq(newUsername))).thenReturn(Optional.empty());

    when(userQueryService.findById(eq(user.getId()))).thenReturn(Optional.of(userData));

    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(param)
        .when()
        .put("/user")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("email already exist"))
        .body("errors.username", nullValue());

    verify(userRepository, never()).save(any());
  }

  private HashMap<String, Object> prepareUpdateParam(
      final String newEmail, final String newBio, final String newUsername) {
    return new HashMap<String, Object>() {
      {
        put(
            "user",
            new HashMap<String, Object>() {
              {
                put("email", newEmail);
                put("bio", newBio);
                put("username", newUsername);
              }
            });
      }
    };
  }

  @Test
  public void should_get_401_if_not_login() throws Exception {
    given()
        .contentType("application/json")
        .body(
            new HashMap<String, Object>() {
              {
                put("user", new HashMap<String, Object>());
              }
            })
        .when()
        .put("/user")
        .then()
        .statusCode(401);
  }
}
