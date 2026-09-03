package io.spring.user.infrastructure.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.user.core.user.FollowRelation;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserRepository;
import io.spring.user.core.user.UserUpdate;
import io.spring.user.infrastructure.DbTestBase;
import io.spring.user.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@Import(MyBatisUserRepository.class)
public class MyBatisUserRepositoryTest extends DbTestBase {
  @Autowired private UserRepository userRepository;

  private static final String HASH = "$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u";

  @BeforeEach
  public void setUp() {
    userRepository.save(new User("user-1", "johndoe", "john@example.com", HASH, "bio-1", "img-1"));
    userRepository.save(new User("user-2", "janedoe", "jane@example.com", HASH, "bio-2", "img-2"));
    userRepository.save(new User("user-3", "bobsmith", "bob@example.com", HASH, "bio-3", "img-3"));
  }

  @Test
  public void should_save_and_find_by_id_username_and_email_with_hash_verbatim() {
    Optional<User> byId = userRepository.findById("user-1");
    assertTrue(byId.isPresent());
    assertEquals("johndoe", byId.get().getUsername());
    assertEquals(HASH, byId.get().getPassword());
    assertEquals("user-1", userRepository.findByUsername("johndoe").get().getId());
    assertEquals("user-1", userRepository.findByEmail("john@example.com").get().getId());
    assertFalse(userRepository.findById("nope").isPresent());
    assertFalse(userRepository.findByUsername("nope").isPresent());
    assertFalse(userRepository.findByEmail("nope").isPresent());
  }

  @Test
  public void should_enforce_unique_username_and_email() {
    assertThrows(
        DataAccessException.class,
        () -> userRepository.save(new User("user-9", "johndoe", "x@example.com", HASH, "", "")));
    assertThrows(
        DataAccessException.class,
        () -> userRepository.save(new User("user-9", "x", "john@example.com", HASH, "", "")));
    assertThrows(
        DataAccessException.class,
        () -> userRepository.save(new User("user-1", "x", "x@example.com", HASH, "", "")));
  }

  @Test
  public void should_find_by_ids_and_ignore_unknown_or_empty() {
    List<User> users = userRepository.findByIds(Arrays.asList("user-1", "user-3", "ghost"));
    assertEquals(2, users.size());
    assertTrue(users.stream().anyMatch(u -> u.getId().equals("user-1")));
    assertTrue(users.stream().anyMatch(u -> u.getId().equals("user-3")));
    assertTrue(userRepository.findByIds(Collections.emptyList()).isEmpty());
    assertTrue(userRepository.findByIds(null).isEmpty());
  }

  @Test
  public void should_skip_null_and_blank_fields_on_update() {
    userRepository.update(new UserUpdate("user-1", "", null, "", "new bio", null));
    User user = userRepository.findById("user-1").get();
    assertEquals("johndoe", user.getUsername());
    assertEquals("john@example.com", user.getEmail());
    assertEquals(HASH, user.getPassword());
    assertEquals("new bio", user.getBio());
    assertEquals("img-1", user.getImage());

    userRepository.update(new UserUpdate("user-1", "john2", "john2@example.com", "h2", null, "i2"));
    user = userRepository.findById("user-1").get();
    assertEquals("john2", user.getUsername());
    assertEquals("john2@example.com", user.getEmail());
    assertEquals("h2", user.getPassword());
    assertEquals("new bio", user.getBio());
    assertEquals("i2", user.getImage());
  }

  @Test
  public void should_reject_update_to_taken_username_or_email() {
    assertThrows(
        DataAccessException.class,
        () -> userRepository.update(new UserUpdate("user-1", "janedoe", null, null, null, null)));
    assertThrows(
        DataAccessException.class,
        () ->
            userRepository.update(
                new UserUpdate("user-1", null, "jane@example.com", null, null, null)));
  }

  @Test
  public void should_save_relation_once_and_remove_idempotently() {
    FollowRelation relation = new FollowRelation("user-1", "user-2");
    userRepository.saveRelation(relation);
    userRepository.saveRelation(relation);
    assertTrue(userRepository.findRelation("user-1", "user-2").isPresent());
    assertFalse(userRepository.findRelation("user-2", "user-1").isPresent());
    assertEquals(Collections.singletonList("user-2"), userRepository.followedUsers("user-1"));

    userRepository.removeRelation(relation);
    assertFalse(userRepository.findRelation("user-1", "user-2").isPresent());
    userRepository.removeRelation(relation);
    assertTrue(userRepository.followedUsers("user-1").isEmpty());
  }

  @Test
  public void should_return_following_subset_of_ids() {
    userRepository.saveRelation(new FollowRelation("user-3", "user-1"));
    userRepository.saveRelation(new FollowRelation("user-3", "user-2"));
    userRepository.saveRelation(new FollowRelation("user-1", "user-2"));
    assertEquals(
        Arrays.asList("user-1", "user-2"),
        sorted(userRepository.followingAuthors("user-3", Arrays.asList("user-1", "user-2", "x"))));
    assertEquals(
        Collections.singletonList("user-2"),
        userRepository.followingAuthors("user-1", Arrays.asList("user-1", "user-2", "user-3")));
    assertTrue(userRepository.followingAuthors("user-2", Arrays.asList("user-1")).isEmpty());
    assertTrue(userRepository.followingAuthors("user-3", Collections.emptyList()).isEmpty());
  }

  @Test
  public void should_list_followed_users_in_insertion_order() {
    userRepository.saveRelation(new FollowRelation("user-3", "user-2"));
    userRepository.saveRelation(new FollowRelation("user-3", "user-1"));
    userRepository.saveRelation(new FollowRelation("user-3", "user-9"));
    assertEquals(
        Arrays.asList("user-2", "user-1", "user-9"), userRepository.followedUsers("user-3"));
    assertTrue(userRepository.followedUsers("user-1").isEmpty());
  }

  private static List<String> sorted(List<String> list) {
    List<String> copy = new java.util.ArrayList<>(list);
    Collections.sort(copy);
    return copy;
  }
}
