package io.spring.application.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class DuplicatedUsernameValidatorTest {
  private UserRepository userRepository;
  private DuplicatedUsernameValidator validator;

  @BeforeEach
  public void setUp() {
    userRepository = mock(UserRepository.class);
    validator = new DuplicatedUsernameValidator();
    ReflectionTestUtils.setField(validator, "userRepository", userRepository);
  }

  @Test
  public void should_reject_existing_username() {
    when(userRepository.findByUsername(eq("johnjacob")))
        .thenReturn(Optional.of(new User("john@jacob.com", "johnjacob", "123", "", "")));

    Assertions.assertFalse(validator.isValid("johnjacob", null));
  }

  @Test
  public void should_accept_new_username() {
    when(userRepository.findByUsername(eq("newname"))).thenReturn(Optional.empty());

    Assertions.assertTrue(validator.isValid("newname", null));
  }

  @Test
  public void should_accept_null_or_empty_username_without_lookup() {
    Assertions.assertTrue(validator.isValid(null, null));
    Assertions.assertTrue(validator.isValid("", null));
  }
}
