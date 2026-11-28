package io.spring.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.application.readservice.UserReadService;
import io.spring.application.readservice.UserRelationshipQueryService;
import io.spring.core.user.User;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProfileQueryServiceUnitTest {
  private UserReadService userReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private ProfileQueryService profileQueryService;
  private User currentUser;

  @BeforeEach
  public void setUp() {
    userReadService = mock(UserReadService.class);
    userRelationshipQueryService = mock(UserRelationshipQueryService.class);
    profileQueryService = new ProfileQueryService(userReadService, userRelationshipQueryService);
    currentUser = new User("john@jacob.com", "johnjacob", "123", "", "");
    when(userReadService.findByUsername(eq("target")))
        .thenReturn(new UserData("target-id", "target@test.com", "target", "bio", "image"));
  }

  @Test
  public void should_return_empty_for_unknown_username() {
    when(userReadService.findByUsername(eq("missing"))).thenReturn(null);

    Assertions.assertFalse(profileQueryService.findByUsername("missing", currentUser).isPresent());
  }

  @Test
  public void should_return_profile_followed_by_current_user() {
    when(userRelationshipQueryService.isUserFollowing(eq(currentUser.getId()), eq("target-id")))
        .thenReturn(true);

    Optional<ProfileData> profile = profileQueryService.findByUsername("target", currentUser);

    Assertions.assertTrue(profile.isPresent());
    Assertions.assertEquals("target-id", profile.get().getId());
    Assertions.assertEquals("target", profile.get().getUsername());
    Assertions.assertEquals("bio", profile.get().getBio());
    Assertions.assertEquals("image", profile.get().getImage());
    Assertions.assertTrue(profile.get().isFollowing());
  }

  @Test
  public void should_return_not_following_for_anonymous_user() {
    Optional<ProfileData> profile = profileQueryService.findByUsername("target", null);

    Assertions.assertFalse(profile.get().isFollowing());
    verify(userRelationshipQueryService, never()).isUserFollowing(any(), any());
  }
}
