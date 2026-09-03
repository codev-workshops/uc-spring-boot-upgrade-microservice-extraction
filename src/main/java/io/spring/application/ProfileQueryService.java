package io.spring.application;

import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.application.user.FollowPort;
import io.spring.application.user.UserQueryPort;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProfileQueryService {
  private final UserReadService userReadService;
  private final UserRelationshipQueryService userRelationshipQueryService;
  private final UserQueryPort userQueryPort;
  private final FollowPort followPort;

  @Autowired
  public ProfileQueryService(
      UserReadService userReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      ObjectProvider<UserQueryPort> userQueryPort,
      ObjectProvider<FollowPort> followPort) {
    this(
        userReadService,
        userRelationshipQueryService,
        userQueryPort.getIfAvailable(),
        followPort.getIfAvailable());
  }

  public ProfileQueryService(
      UserReadService userReadService, UserRelationshipQueryService userRelationshipQueryService) {
    this(userReadService, userRelationshipQueryService, (UserQueryPort) null, (FollowPort) null);
  }

  public ProfileQueryService(
      UserReadService userReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      UserQueryPort userQueryPort,
      FollowPort followPort) {
    this.userReadService = userReadService;
    this.userRelationshipQueryService = userRelationshipQueryService;
    this.userQueryPort = userQueryPort;
    this.followPort = followPort;
  }

  public Optional<ProfileData> findByUsername(String username, User currentUser) {
    UserData userData = findUser(username);
    if (userData == null) {
      return Optional.empty();
    } else {
      ProfileData profileData =
          new ProfileData(
              userData.getId(),
              userData.getUsername(),
              userData.getBio(),
              userData.getImage(),
              currentUser != null && isFollowing(currentUser.getId(), userData.getId()));
      return Optional.of(profileData);
    }
  }

  private UserData findUser(String username) {
    if (userQueryPort != null && userQueryPort.ownsUserReads()) {
      return userQueryPort.findByUsername(username).orElse(null);
    }
    return userReadService.findByUsername(username);
  }

  private boolean isFollowing(String userId, String targetId) {
    if (followPort != null && followPort.ownsFollowReads()) {
      return followPort.isFollowing(userId, targetId);
    }
    return userRelationshipQueryService.isUserFollowing(userId, targetId);
  }
}
