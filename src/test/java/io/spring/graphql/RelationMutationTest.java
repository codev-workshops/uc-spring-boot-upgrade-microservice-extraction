package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.ProfileQueryService;
import io.spring.application.data.ProfileData;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.graphql.exception.AuthenticationException;
import io.spring.graphql.types.ProfilePayload;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class RelationMutationTest extends GraphQLTestBase {
  private UserRepository userRepository;
  private ProfileQueryService profileQueryService;
  private RelationMutation relationMutation;
  private User user;
  private User target;

  @BeforeEach
  public void setUp() {
    userRepository = mock(UserRepository.class);
    profileQueryService = mock(ProfileQueryService.class);
    relationMutation = new RelationMutation(userRepository, profileQueryService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    target = new User("target@test.com", "target", "123", "bio", "image");
  }

  private ProfileData targetProfile(boolean following) {
    return new ProfileData(target.getId(), "target", "bio", "image", following);
  }

  @Test
  public void should_follow_user() {
    login(user);
    when(userRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
    when(profileQueryService.findByUsername(eq("target"), eq(user)))
        .thenReturn(Optional.of(targetProfile(true)));

    ProfilePayload payload = relationMutation.follow("target");

    ArgumentCaptor<FollowRelation> captor = ArgumentCaptor.forClass(FollowRelation.class);
    verify(userRepository).saveRelation(captor.capture());
    Assertions.assertEquals(user.getId(), captor.getValue().getUserId());
    Assertions.assertEquals(target.getId(), captor.getValue().getTargetId());
    Assertions.assertEquals("target", payload.getProfile().getUsername());
    Assertions.assertTrue(payload.getProfile().getFollowing());
  }

  @Test
  public void should_throw_not_found_when_following_unknown_user() {
    login(user);
    when(userRepository.findByUsername(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> relationMutation.follow("missing"));
    verify(userRepository, never()).saveRelation(any());
  }

  @Test
  public void should_reject_follow_for_anonymous_user() {
    anonymous();

    Assertions.assertThrows(AuthenticationException.class, () -> relationMutation.follow("target"));
  }

  @Test
  public void should_unfollow_user() {
    login(user);
    FollowRelation relation = new FollowRelation(user.getId(), target.getId());
    when(userRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
    when(userRepository.findRelation(eq(user.getId()), eq(target.getId())))
        .thenReturn(Optional.of(relation));
    when(profileQueryService.findByUsername(eq("target"), eq(user)))
        .thenReturn(Optional.of(targetProfile(false)));

    ProfilePayload payload = relationMutation.unfollow("target");

    verify(userRepository).removeRelation(eq(relation));
    Assertions.assertFalse(payload.getProfile().getFollowing());
  }

  @Test
  public void should_throw_not_found_when_unfollowing_user_not_followed() {
    login(user);
    when(userRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
    when(userRepository.findRelation(eq(user.getId()), eq(target.getId())))
        .thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> relationMutation.unfollow("target"));
    verify(userRepository, never()).removeRelation(any());
  }

  @Test
  public void should_throw_not_found_when_unfollowing_unknown_user() {
    login(user);
    when(userRepository.findByUsername(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> relationMutation.unfollow("missing"));
  }

  @Test
  public void should_reject_unfollow_for_anonymous_user() {
    anonymous();

    Assertions.assertThrows(
        AuthenticationException.class, () -> relationMutation.unfollow("target"));
  }
}
