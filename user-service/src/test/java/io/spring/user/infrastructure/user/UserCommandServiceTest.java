package io.spring.user.infrastructure.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.user.api.exception.InvalidRequestException;
import io.spring.user.api.exception.ResourceNotFoundException;
import io.spring.user.application.UserCommandService;
import io.spring.user.application.UserCommandService.CreateResult;
import io.spring.user.application.UserQueryService;
import io.spring.user.application.data.UserData;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserRepository;
import io.spring.user.core.user.UserUpdate;
import io.spring.user.infrastructure.DbTestBase;
import io.spring.user.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Import({
  MyBatisUserRepository.class,
  UserCommandService.class,
  UserQueryService.class,
  BCryptPasswordEncoder.class
})
public class UserCommandServiceTest extends DbTestBase {
  @Autowired private UserRepository userRepository;
  @Autowired private UserCommandService commandService;
  @Autowired private UserQueryService queryService;

  /** BCrypt of "password123" (the monolith seed). */
  private static final String HASH = "$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u";

  @BeforeEach
  public void setUp() {
    userRepository.save(new User("user-1", "johndoe", "john@example.com", HASH, "bio", "img"));
  }

  @Test
  public void create_is_idempotent_by_id_and_never_exposes_hash() {
    CreateResult first =
        commandService.create(new User("user-2", "jane", "jane@example.com", HASH, "", "i"));
    assertTrue(first.isCreated());
    assertEquals("jane", first.getUser().getUsername());
    CreateResult again =
        commandService.create(new User("user-2", "other", "other@example.com", "x", "", ""));
    assertFalse(again.isCreated());
    assertEquals("jane", again.getUser().getUsername());
    assertEquals(HASH, userRepository.findById("user-2").get().getPassword());
  }

  @Test
  public void create_rejects_username_or_email_held_by_another_id() {
    InvalidRequestException byUsername =
        assertThrows(
            InvalidRequestException.class,
            () ->
                commandService.create(
                    new User("user-9", "johndoe", "new@example.com", HASH, "", "")));
    assertEquals("username", byUsername.getField());
    assertEquals("duplicated username", byUsername.getMessage());
    InvalidRequestException byEmail =
        assertThrows(
            InvalidRequestException.class,
            () ->
                commandService.create(new User("user-9", "new", "john@example.com", HASH, "", "")));
    assertEquals("email", byEmail.getField());
    assertEquals("duplicated email", byEmail.getMessage());
  }

  @Test
  public void update_skips_blank_fields_and_checks_uniqueness_against_other_ids() {
    userRepository.save(new User("user-2", "jane", "jane@example.com", HASH, "", ""));
    UserData updated =
        commandService.update(new UserUpdate("user-1", "johndoe", "", "", "new bio", null));
    assertEquals("johndoe", updated.getUsername());
    assertEquals("john@example.com", updated.getEmail());
    assertEquals("new bio", updated.getBio());
    assertEquals("img", updated.getImage());
    assertEquals(HASH, userRepository.findById("user-1").get().getPassword());

    assertThrows(
        InvalidRequestException.class,
        () -> commandService.update(new UserUpdate("user-1", "jane", null, null, null, null)));
    assertThrows(
        InvalidRequestException.class,
        () ->
            commandService.update(
                new UserUpdate("user-1", null, "jane@example.com", null, null, null)));
    assertThrows(
        ResourceNotFoundException.class,
        () -> commandService.update(new UserUpdate("ghost", "x", null, null, null, null)));
  }

  @Test
  public void update_writes_password_only_when_hash_non_blank() {
    commandService.update(new UserUpdate("user-1", null, null, "$2a$10$newhash", null, null));
    assertEquals("$2a$10$newhash", userRepository.findById("user-1").get().getPassword());
    commandService.update(new UserUpdate("user-1", null, null, "", "b", null));
    assertEquals("$2a$10$newhash", userRepository.findById("user-1").get().getPassword());
  }

  @Test
  public void verify_credentials_matches_bcrypt_and_is_false_for_unknown_or_null() {
    assertTrue(commandService.verifyCredentials("user-1", "password123"));
    assertFalse(commandService.verifyCredentials("user-1", "wrong"));
    assertFalse(commandService.verifyCredentials("user-1", null));
    assertFalse(commandService.verifyCredentials("ghost", "password123"));
  }

  @Test
  public void follow_and_unfollow_are_idempotent() {
    commandService.follow("user-1", "user-2");
    commandService.follow("user-1", "user-2");
    assertTrue(queryService.isFollowing("user-1", "user-2"));
    assertEquals(Arrays.asList("user-2"), queryService.followedUsers("user-1"));
    assertEquals(
        Arrays.asList("user-2"),
        queryService.followingAuthors("user-1", Arrays.asList("user-2", "user-3")));
    assertTrue(queryService.followingAuthors("user-1", null).isEmpty());
    commandService.unfollow("user-1", "user-2");
    commandService.unfollow("user-1", "user-2");
    assertFalse(queryService.isFollowing("user-1", "user-2"));
    assertTrue(queryService.findByIds(null).isEmpty());
    assertEquals(1, queryService.findByIds(Arrays.asList("user-1")).size());
  }
}
