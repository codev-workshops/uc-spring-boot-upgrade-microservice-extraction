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
    Assertions.assertFalse(profileData.isFollowing());
  }

  @Test
  public void should_return_empty_when_user_not_found() {
    Optional<ProfileData> optional = profileQueryService.findByUsername("nobody", null);
    Assertions.assertFalse(optional.isPresent());
  }

  @Test
  public void should_not_be_following_without_current_user() {
    User profileUser = new User("p@test.com", "p", "123", "", "");
    userRepository.save(profileUser);

    Optional<ProfileData> optional =
        profileQueryService.findByUsername(profileUser.getUsername(), null);
    Assertions.assertTrue(optional.isPresent());
    Assertions.assertFalse(optional.get().isFollowing());
  }

  @Test
  public void should_be_following_when_current_user_follows_profile_user() {
    User currentUser = new User("a@test.com", "a", "123", "", "");
    User profileUser = new User("p@test.com", "p", "123", "", "");
    userRepository.save(currentUser);
    userRepository.save(profileUser);
    userRepository.saveRelation(new FollowRelation(currentUser.getId(), profileUser.getId()));

    Optional<ProfileData> optional =
        profileQueryService.findByUsername(profileUser.getUsername(), currentUser);
    Assertions.assertTrue(optional.isPresent());
    Assertions.assertTrue(optional.get().isFollowing());
  }
}
