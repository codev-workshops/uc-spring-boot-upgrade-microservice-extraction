package io.spring.application.profile;

import io.spring.application.ProfileQueryService;
import io.spring.application.data.ProfileData;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({ProfileQueryService.class, MyBatisUserRepository.class})
public class ProfileQueryServiceTest extends DbTestBase {
  @Autowired private ProfileQueryService profileQueryService;
  @Autowired private UserRepository userRepository;

  @Test
  public void should_fetch_profile_success() {
    User currentUser = new User("a@test.com", "a", "123", "", "");
    User profileUser = new User("p@test.com", "p", "123", "", "");
    userRepository.save(profileUser);

    Optional<ProfileData> optional =
        profileQueryService.findByUsername(profileUser.getUsername(), currentUser);
    Assertions.assertTrue(optional.isPresent());
    ProfileData profileData = optional.get();
    Assertions.assertEquals(profileUser.getId(), profileData.getId());
    Assertions.assertEquals(profileUser.getUsername(), profileData.getUsername());
    Assertions.assertEquals(profileUser.getBio(), profileData.getBio());
    Assertions.assertEquals(profileUser.getImage(), profileData.getImage());
    Assertions.assertFalse(profileData.isFollowing());
  }

  @Test
  public void should_fetch_profile_with_bio_and_image() {
    User profileUser = new User("bio@test.com", "bio", "123", "my bio", "http://img/me.png");
    userRepository.save(profileUser);

    ProfileData profileData = profileQueryService.findByUsername("bio", null).get();
    Assertions.assertEquals("my bio", profileData.getBio());
    Assertions.assertEquals("http://img/me.png", profileData.getImage());
    Assertions.assertFalse(profileData.isFollowing());
  }

  @Test
  public void should_show_following_when_current_user_follows_profile() {
    User currentUser = new User("a@test.com", "a", "123", "", "");
    User profileUser = new User("p@test.com", "p", "123", "", "");
    userRepository.save(currentUser);
    userRepository.save(profileUser);
    userRepository.saveRelation(new FollowRelation(currentUser.getId(), profileUser.getId()));

    Assertions.assertTrue(
        profileQueryService
            .findByUsername(profileUser.getUsername(), currentUser)
            .get()
            .isFollowing());
    Assertions.assertFalse(
        profileQueryService
            .findByUsername(currentUser.getUsername(), profileUser)
            .get()
            .isFollowing());
    Assertions.assertFalse(
        profileQueryService.findByUsername(profileUser.getUsername(), null).get().isFollowing());
  }

  @Test
  public void should_return_empty_for_unknown_username() {
    Assertions.assertFalse(profileQueryService.findByUsername("nobody", null).isPresent());
  }
}
