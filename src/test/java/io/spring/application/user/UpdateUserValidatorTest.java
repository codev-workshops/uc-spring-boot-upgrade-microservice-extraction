package io.spring.application.user;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import java.util.Optional;
import javax.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class UpdateUserValidatorTest {
  private UserRepository userRepository;
  private UpdateUserValidator validator;
  private ConstraintValidatorContext context;
  private User user;
  private User other;

  @BeforeEach
  public void setUp() {
    userRepository = mock(UserRepository.class);
    validator = new UpdateUserValidator();
    ReflectionTestUtils.setField(validator, "userRepository", userRepository);
    context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    other = new User("other@test.com", "other", "123", "", "");
  }

  private UpdateUserCommand command(String email, String username) {
    return new UpdateUserCommand(
        user, UpdateUserParam.builder().email(email).username(username).build());
  }

  @Test
  public void should_accept_unchanged_email_and_username_of_same_user() {
    when(userRepository.findByEmail(eq("john@jacob.com"))).thenReturn(Optional.of(user));
    when(userRepository.findByUsername(eq("johnjacob"))).thenReturn(Optional.of(user));

    Assertions.assertTrue(validator.isValid(command("john@jacob.com", "johnjacob"), context));
    verify(context, never()).disableDefaultConstraintViolation();
  }

  @Test
  public void should_accept_new_unused_email_and_username() {
    when(userRepository.findByEmail(eq("new@test.com"))).thenReturn(Optional.empty());
    when(userRepository.findByUsername(eq("newname"))).thenReturn(Optional.empty());

    Assertions.assertTrue(validator.isValid(command("new@test.com", "newname"), context));
  }

  @Test
  public void should_reject_email_used_by_another_user() {
    when(userRepository.findByEmail(eq("other@test.com"))).thenReturn(Optional.of(other));
    when(userRepository.findByUsername(eq("johnjacob"))).thenReturn(Optional.of(user));

    Assertions.assertFalse(validator.isValid(command("other@test.com", "johnjacob"), context));
    verify(context).disableDefaultConstraintViolation();
    verify(context).buildConstraintViolationWithTemplate(eq("email already exist"));
    verify(context, never()).buildConstraintViolationWithTemplate(eq("username already exist"));
  }

  @Test
  public void should_reject_username_used_by_another_user() {
    when(userRepository.findByEmail(eq("john@jacob.com"))).thenReturn(Optional.of(user));
    when(userRepository.findByUsername(eq("other"))).thenReturn(Optional.of(other));

    Assertions.assertFalse(validator.isValid(command("john@jacob.com", "other"), context));
    verify(context).buildConstraintViolationWithTemplate(eq("username already exist"));
    verify(context, never()).buildConstraintViolationWithTemplate(eq("email already exist"));
  }

  @Test
  public void should_reject_both_when_email_and_username_taken() {
    when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(other));
    when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(other));

    Assertions.assertFalse(validator.isValid(command("other@test.com", "other"), context));
    verify(context).buildConstraintViolationWithTemplate(eq("email already exist"));
    verify(context).buildConstraintViolationWithTemplate(eq("username already exist"));
  }
}
