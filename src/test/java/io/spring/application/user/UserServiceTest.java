package io.spring.application.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
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
    when(passwordEncoder.encode(any())).thenAnswer(inv -> "encoded-" + inv.getArgument(0));
    userService = new UserService(userRepository, "default-image.png", passwordEncoder);
  }

  @Test
  public void should_create_user_with_encoded_password_and_default_image() {
    User created = userService.createUser(new RegisterParam("john@test.com", "john", "secret"));

    assertNotNull(created);
    assertNotNull(created.getId());
    assertEquals("john@test.com", created.getEmail());
    assertEquals("john", created.getUsername());
    assertEquals("encoded-secret", created.getPassword());
    assertEquals("", created.getBio());
    assertEquals("default-image.png", created.getImage());

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertSame(created, captor.getValue());
  }

  @Test
  public void should_update_user_fields_and_persist() {
    User user = new User("old@test.com", "old", "123", "old bio", "old.png");
    UpdateUserParam param =
        UpdateUserParam.builder()
            .email("new@test.com")
            .username("new")
            .password("456")
            .bio("new bio")
            .image("new.png")
            .build();

    userService.updateUser(new UpdateUserCommand(user, param));

    assertEquals("new@test.com", user.getEmail());
    assertEquals("new", user.getUsername());
    assertEquals("456", user.getPassword());
    assertEquals("new bio", user.getBio());
    assertEquals("new.png", user.getImage());
    verify(userRepository).save(user);
  }

  @Test
  public void should_keep_existing_fields_when_update_params_are_blank() {
    User user = new User("old@test.com", "old", "123", "old bio", "old.png");

    userService.updateUser(new UpdateUserCommand(user, UpdateUserParam.builder().build()));

    assertEquals("old@test.com", user.getEmail());
    assertEquals("old", user.getUsername());
    assertEquals("123", user.getPassword());
    assertEquals("old bio", user.getBio());
    assertEquals("old.png", user.getImage());
    verify(userRepository).save(user);
  }
}
