package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import graphql.execution.DataFetcherResult;
import io.spring.api.exception.InvalidAuthenticationException;
import io.spring.application.user.RegisterParam;
import io.spring.application.user.UpdateUserCommand;
import io.spring.application.user.UserService;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.graphql.types.CreateUserInput;
import io.spring.graphql.types.Error;
import io.spring.graphql.types.UpdateUserInput;
import io.spring.graphql.types.UserPayload;
import io.spring.graphql.types.UserResult;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validation;
import javax.validation.constraints.NotBlank;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

public class UserMutationTest extends GraphQLTestBase {
  private UserRepository userRepository;
  private PasswordEncoder passwordEncoder;
  private UserService userService;
  private UserMutation userMutation;
  private User user;

  @BeforeEach
  public void setUp() {
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    userService = mock(UserService.class);
    userMutation = new UserMutation(userRepository, passwordEncoder, userService);
    user = new User("john@jacob.com", "johnjacob", "encoded", "", "");
  }

  static class EmailHolder {
    @NotBlank(message = "can't be empty")
    String email = "";
  }

  @Test
  public void should_create_user() {
    when(userService.createUser(any())).thenReturn(user);
    CreateUserInput input =
        CreateUserInput.newBuilder()
            .email("john@jacob.com")
            .username("johnjacob")
            .password("123")
            .build();

    DataFetcherResult<UserResult> result = userMutation.createUser(input);

    ArgumentCaptor<RegisterParam> captor = ArgumentCaptor.forClass(RegisterParam.class);
    verify(userService).createUser(captor.capture());
    Assertions.assertEquals("john@jacob.com", captor.getValue().getEmail());
    Assertions.assertEquals("johnjacob", captor.getValue().getUsername());
    Assertions.assertEquals("123", captor.getValue().getPassword());
    Assertions.assertTrue(result.getData() instanceof UserPayload);
    Assertions.assertEquals(user, result.getLocalContext());
  }

  @Test
  public void should_return_errors_as_data_when_create_user_is_invalid() {
    Set<ConstraintViolation<EmailHolder>> violations =
        Validation.buildDefaultValidatorFactory().getValidator().validate(new EmailHolder());
    when(userService.createUser(any())).thenThrow(new ConstraintViolationException(violations));
    CreateUserInput input =
        CreateUserInput.newBuilder().email("").username("johnjacob").password("123").build();

    DataFetcherResult<UserResult> result = userMutation.createUser(input);

    Assertions.assertTrue(result.getData() instanceof Error);
    Error error = (Error) result.getData();
    Assertions.assertEquals("BAD_REQUEST", error.getMessage());
    Assertions.assertEquals(1, error.getErrors().size());
    Assertions.assertEquals("email", error.getErrors().get(0).getKey());
    Assertions.assertEquals(
        Collections.singletonList("can't be empty"), error.getErrors().get(0).getValue());
    Assertions.assertNull(result.getLocalContext());
  }

  @Test
  public void should_login_with_correct_password() {
    when(userRepository.findByEmail(eq("john@jacob.com"))).thenReturn(Optional.of(user));
    when(passwordEncoder.matches(eq("123"), eq("encoded"))).thenReturn(true);

    DataFetcherResult<UserPayload> result = userMutation.login("123", "john@jacob.com");

    Assertions.assertNotNull(result.getData());
    Assertions.assertEquals(user, result.getLocalContext());
  }

  @Test
  public void should_reject_login_with_wrong_password() {
    when(userRepository.findByEmail(eq("john@jacob.com"))).thenReturn(Optional.of(user));
    when(passwordEncoder.matches(eq("wrong"), eq("encoded"))).thenReturn(false);

    Assertions.assertThrows(
        InvalidAuthenticationException.class, () -> userMutation.login("wrong", "john@jacob.com"));
  }

  @Test
  public void should_reject_login_with_unknown_email() {
    when(userRepository.findByEmail(eq("missing@test.com"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        InvalidAuthenticationException.class, () -> userMutation.login("123", "missing@test.com"));
  }

  @Test
  public void should_update_current_user() {
    login(user);
    UpdateUserInput input =
        UpdateUserInput.newBuilder()
            .email("new@test.com")
            .username("newname")
            .bio("bio")
            .password("456")
            .image("image")
            .build();

    DataFetcherResult<UserPayload> result = userMutation.updateUser(input);

    ArgumentCaptor<UpdateUserCommand> captor = ArgumentCaptor.forClass(UpdateUserCommand.class);
    verify(userService).updateUser(captor.capture());
    Assertions.assertEquals(user, captor.getValue().getTargetUser());
    Assertions.assertEquals("new@test.com", captor.getValue().getParam().getEmail());
    Assertions.assertEquals("newname", captor.getValue().getParam().getUsername());
    Assertions.assertEquals("bio", captor.getValue().getParam().getBio());
    Assertions.assertEquals("456", captor.getValue().getParam().getPassword());
    Assertions.assertEquals("image", captor.getValue().getParam().getImage());
    Assertions.assertEquals(user, result.getLocalContext());
  }

  @Test
  public void should_return_null_when_anonymous_user_updates() {
    anonymous();

    Assertions.assertNull(userMutation.updateUser(UpdateUserInput.newBuilder().build()));
    verifyNoInteractions(userService);
  }
}
