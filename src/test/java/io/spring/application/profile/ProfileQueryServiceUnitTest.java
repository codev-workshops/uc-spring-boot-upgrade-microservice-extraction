package io.spring.application.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.ProfileQueryService;
import io.spring.application.data.UserData;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ProfileQueryServiceUnitTest {
  @Mock private UserReadService userReadService;
  @Mock private UserRelationshipQueryService userRelationshipQueryService;

  private ProfileQueryService profileQueryService;
  private UserData userData;
  private User currentUser;

  @BeforeEach
  public void setUp() {
    profileQueryService = new ProfileQueryService(userReadService, userRelationshipQueryService);
    userData = new UserData("user-id", "email@example.com", "username", "bio", "image");
    currentUser = new User("current@example.com", "current", "password", "", "");
  }

  @Test
  public void returnsEmptyWhenUserIsNotFound() {
    when(userReadService.findByUsername("username")).thenReturn(null);

    assertEquals(Optional.empty(), profileQueryService.findByUsername("username", currentUser));
    verifyNoInteractions(userRelationshipQueryService);
  }

  @Test
  public void returnsProfileWithoutFollowingWhenCurrentUserIsNull() {
    when(userReadService.findByUsername("username")).thenReturn(userData);

    var profile = profileQueryService.findByUsername("username", null);

    assertTrue(profile.isPresent());
    assertEquals("user-id", profile.get().getId());
    assertEquals("username", profile.get().getUsername());
    assertEquals("bio", profile.get().getBio());
    assertEquals("image", profile.get().getImage());
    assertFalse(profile.get().isFollowing());
    verifyNoInteractions(userRelationshipQueryService);
  }

  @Test
  public void returnsFollowingProfileWhenCurrentUserFollowsUser() {
    when(userReadService.findByUsername("username")).thenReturn(userData);
    when(userRelationshipQueryService.isUserFollowing(currentUser.getId(), "user-id"))
        .thenReturn(true);

    var profile = profileQueryService.findByUsername("username", currentUser);

    assertTrue(profile.isPresent());
    assertTrue(profile.get().isFollowing());
    verify(userRelationshipQueryService).isUserFollowing(currentUser.getId(), "user-id");
  }

  @Test
  public void returnsNonFollowingProfileWhenCurrentUserDoesNotFollowUser() {
    when(userReadService.findByUsername("username")).thenReturn(userData);
    when(userRelationshipQueryService.isUserFollowing(currentUser.getId(), "user-id"))
        .thenReturn(false);

    var profile = profileQueryService.findByUsername("username", currentUser);

    assertTrue(profile.isPresent());
    assertFalse(profile.get().isFollowing());
  }
}
