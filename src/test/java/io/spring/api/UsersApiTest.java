package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.UserQueryService;
import io.spring.application.data.UserData;
import io.spring.application.user.RegisterParam;
import io.spring.application.user.UserService;
import io.spring.core.service.JwtService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Path;
import javax.validation.constraints.Email;
import javax.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UsersApi.class)
@Import({
  WebSecurityConfig.class,
  UserQueryService.class,
  BCryptPasswordEncoder.class,
  JacksonCustomizations.class
})
public class UsersApiTest {
  @Autowired private MockMvc mvc;

  @MockBean private UserRepository userRepository;

  @MockBean private JwtService jwtService;

  @MockBean private UserReadService userReadService;

  @MockBean private UserService userService;

  @Autowired private PasswordEncoder passwordEncoder;

  private String defaultAvatar;

  @BeforeEach
  public void setUp() throws Exception {
    RestAssuredMockMvc.mockMvc(mvc);
    defaultAvatar = "https://static.productionready.io/images/smiley-cyrus.jpg";
  }

  @Test
  public void should_create_user_success() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob";

    when(jwtService.toToken(any())).thenReturn("123");
    User user = new User(email, username, "123", "", defaultAvatar);
    UserData userData = new UserData(user.getId(), email, username, "", defaultAvatar);
    when(userReadService.findById(any())).thenReturn(userData);

    when(userService.createUser(any())).thenReturn(user);

    when(userRepository.findByUsername(eq(username))).thenReturn(Optional.empty());
    when(userRepository.findByEmail(eq(email))).thenReturn(Optional.empty());

    Map<String, Object> param = prepareRegisterParameter(email, username);

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .then()
        .statusCode(201)
        .body("user.email", equalTo(email))
        .body("user.username", equalTo(username))
        .body("user.bio", equalTo(""))
        .body("user.image", equalTo(defaultAvatar))
        .body("user.token", equalTo("123"));

    verify(userService).createUser(any());
  }

  @Test
  public void should_show_error_message_for_blank_username() throws Exception {

    String email = "john@jacob.com";
    String username = "";

    Map<String, Object> param = prepareRegisterParameter(email, username);

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("errors.username[0]", equalTo("can't be empty"));
  }

  @Test
  public void should_show_error_message_for_invalid_email() throws Exception {
    String email = "johnxjacob.com";
    String username = "johnjacob";

    Map<String, Object> param = prepareRegisterParameter(email, username);

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("should be an email"));
  }

  @Test
  public void should_show_error_for_duplicated_username() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob";

    when(userRepository.findByUsername(eq(username)))
        .thenReturn(Optional.of(new User(email, username, "123", "bio", "")));
    when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

    Map<String, Object> param = prepareRegisterParameter(email, username);

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("errors.username[0]", equalTo("duplicated username"));
  }

  @Test
  public void should_show_error_for_duplicated_email() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob2";

    when(userRepository.findByEmail(eq(email)))
        .thenReturn(Optional.of(new User(email, username, "123", "bio", "")));

    when(userRepository.findByUsername(eq(username))).thenReturn(Optional.empty());

    Map<String, Object> param = prepareRegisterParameter(email, username);

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .then()
        .statusCode(422)
        .body("errors.email[0]", equalTo("duplicated email"));
  }

  @Test
  public void should_return_422_with_errors_map_for_every_invalid_register_field()
      throws Exception {
    Map<String, Object> param =
        new HashMap<String, Object>() {
          {
            put(
                "user",
                new HashMap<String, Object>() {
                  {
                    put("email", "not-an-email");
                    put("password", "");
                    put("username", "");
                  }
                });
          }
        };

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users")
        .then()
        .statusCode(422)
        .contentType("application/json")
        .body("keySet()", contains("errors"))
        .body("errors.keySet()", containsInAnyOrder("email", "username", "password"))
        .body("errors.email", contains("should be an email"))
        .body("errors.username", contains("can't be empty"))
        .body("errors.password", contains("can't be empty"));

    verify(userService, never()).createUser(any());
  }

  @Test
  public void should_map_service_constraint_violation_to_422_errors_map() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob";
    when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
    when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
    ConstraintViolationException serviceError =
        new ConstraintViolationException(
            Collections.singleton(violation("createUser.registerParam.email", "duplicated email")));
    when(userService.createUser(any())).thenThrow(serviceError);

    given()
        .contentType("application/json")
        .body(prepareRegisterParameter(email, username))
        .when()
        .post("/users")
        .then()
        .statusCode(422)
        .body("errors.keySet()", contains("email"))
        .body("errors.email", contains("duplicated email"));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ConstraintViolation<?> violation(String path, String message)
      throws NoSuchFieldException {
    ConstraintViolation<RegisterParam> violation = mock(ConstraintViolation.class);
    Path propertyPath = mock(Path.class);
    when(propertyPath.toString()).thenReturn(path);
    ConstraintDescriptor descriptor = mock(ConstraintDescriptor.class);
    when(descriptor.getAnnotation())
        .thenReturn(RegisterParam.class.getDeclaredField("email").getAnnotation(Email.class));
    when(violation.getRootBeanClass()).thenReturn(RegisterParam.class);
    when(violation.getPropertyPath()).thenReturn(propertyPath);
    when(violation.getConstraintDescriptor()).thenReturn(descriptor);
    when(violation.getMessage()).thenReturn(message);
    return violation;
  }

  private HashMap<String, Object> prepareRegisterParameter(
      final String email, final String username) {
    return new HashMap<String, Object>() {
      {
        put(
            "user",
            new HashMap<String, Object>() {
              {
                put("email", email);
                put("password", "johnnyjacob");
                put("username", username);
              }
            });
      }
    };
  }

  @Test
  public void should_login_success() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob2";
    String password = "123";

    User user = new User(email, username, passwordEncoder.encode(password), "", defaultAvatar);
    UserData userData = new UserData("123", email, username, "", defaultAvatar);

    when(userRepository.findByEmail(eq(email))).thenReturn(Optional.of(user));
    when(userReadService.findByUsername(eq(username))).thenReturn(userData);
    when(userReadService.findById(eq(user.getId()))).thenReturn(userData);
    when(jwtService.toToken(any())).thenReturn("123");

    Map<String, Object> param =
        new HashMap<String, Object>() {
          {
            put(
                "user",
                new HashMap<String, Object>() {
                  {
                    put("email", email);
                    put("password", password);
                  }
                });
          }
        };

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users/login")
        .then()
        .statusCode(200)
        .body("user.email", equalTo(email))
        .body("user.username", equalTo(username))
        .body("user.bio", equalTo(""))
        .body("user.image", equalTo(defaultAvatar))
        .body("user.token", equalTo("123"));
    ;
  }

  @Test
  public void should_fail_login_with_wrong_password() throws Exception {
    String email = "john@jacob.com";
    String username = "johnjacob2";
    String password = "123";

    User user = new User(email, username, password, "", defaultAvatar);
    UserData userData = new UserData(user.getId(), email, username, "", defaultAvatar);

    when(userRepository.findByEmail(eq(email))).thenReturn(Optional.of(user));
    when(userReadService.findByUsername(eq(username))).thenReturn(userData);

    Map<String, Object> param =
        new HashMap<String, Object>() {
          {
            put(
                "user",
                new HashMap<String, Object>() {
                  {
                    put("email", email);
                    put("password", "123123");
                  }
                });
          }
        };

    given()
        .contentType("application/json")
        .body(param)
        .when()
        .post("/users/login")
        .prettyPeek()
        .then()
        .statusCode(422)
        .body("message", equalTo("invalid email or password"));
  }
}
