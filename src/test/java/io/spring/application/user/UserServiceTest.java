package io.spring.application.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

public class UserServiceTest {
  private UserRepository userRepository;
  private PasswordEncoder passwordEncoder;
  private UserService userService;

  @BeforeEach
  public void setUp() {
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    userService = new UserService(userRepository, "default-image", passwordEncoder);
  }

  @Test
  public void should_create_user_with_encoded_password_and_default_image() {
    when(passwordEncoder.encode(eq("123"))).thenReturn("encoded");

    User user = userService.createUser(new RegisterParam("john@jacob.com", "johnjacob", "123"));

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    User saved = captor.getValue();
    Assertions.assertEquals(user, saved);
    Assertions.assertEquals("john@jacob.com", saved.getEmail());
    Assertions.assertEquals("johnjacob", saved.getUsername());
    Assertions.assertEquals("encoded", saved.getPassword());
    Assertions.assertEquals("", saved.getBio());
    Assertions.assertEquals("default-image", saved.getImage());
  }

  @Test
  public void should_update_only_provided_fields() {
    User user = new User("john@jacob.com", "johnjacob", "123", "old bio", "old image");
    UpdateUserParam param = UpdateUserParam.builder().email("new@test.com").bio("new bio").build();

    userService.updateUser(new UpdateUserCommand(user, param));

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    User saved = captor.getValue();
    Assertions.assertEquals("new@test.com", saved.getEmail());
    Assertions.assertEquals("johnjacob", saved.getUsername());
    Assertions.assertEquals("123", saved.getPassword());
    Assertions.assertEquals("new bio", saved.getBio());
    Assertions.assertEquals("old image", saved.getImage());
  }
}
