package io.spring.infrastructure.extraction.user;

import io.spring.application.user.FollowPort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** user-service implementation of {@link FollowPort}. */
@Component
public class RemoteFollowAdapter implements FollowPort {
  private final UserServiceClient client;

  public RemoteFollowAdapter(UserServiceClient client) {
    this.client = client;
  }

  @Override
  public boolean isFollowing(String userId, String targetId) {
    return client.isFollowing(userId, targetId);
  }

  @Override
  public Set<String> followingAuthors(String userId, List<String> ids) {
    return new HashSet<>(client.followingIds(userId, ids));
  }

  @Override
  public List<String> followedUsers(String userId) {
    return client.followedIds(userId);
  }

  @Override
  public void follow(String userId, String targetId) {
    client.follow(userId, targetId);
  }

  @Override
  public void unfollow(String userId, String targetId) {
    client.unfollow(userId, targetId);
  }
}
