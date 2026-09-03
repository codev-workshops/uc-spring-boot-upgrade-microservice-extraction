package io.spring.infrastructure.extraction.user;

import io.spring.application.user.FollowPort;
import io.spring.core.user.FollowRelation;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Monolith SQL implementation of {@link FollowPort}. */
@Component
public class LocalFollowAdapter implements FollowPort {
  private final UserRelationshipQueryService relationships;
  private final MyBatisUserRepository repository;

  public LocalFollowAdapter(
      UserRelationshipQueryService relationships, MyBatisUserRepository repository) {
    this.relationships = relationships;
    this.repository = repository;
  }

  @Override
  public boolean isFollowing(String userId, String targetId) {
    return relationships.isUserFollowing(userId, targetId);
  }

  @Override
  public Set<String> followingAuthors(String userId, List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptySet();
    }
    return relationships.followingAuthors(userId, ids);
  }

  @Override
  public List<String> followedUsers(String userId) {
    return relationships.followedUsers(userId);
  }

  @Override
  public void follow(String userId, String targetId) {
    repository.saveRelation(new FollowRelation(userId, targetId));
  }

  @Override
  public void unfollow(String userId, String targetId) {
    repository.removeRelation(new FollowRelation(userId, targetId));
  }
}
