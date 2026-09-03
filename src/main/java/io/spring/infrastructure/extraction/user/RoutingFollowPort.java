package io.spring.infrastructure.extraction.user;

import io.spring.application.user.FollowPort;
import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.ShadowComparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Follow reads are routed exactly like {@link RoutingUserQueryPort} (same {@code extraction.user}
 * route); follow/unfollow writes go through the routing {@link UserCommandPort}.
 */
@Primary
@Service
public class RoutingFollowPort implements FollowPort {
  private static final Logger log = LoggerFactory.getLogger(RoutingFollowPort.class);

  private final LocalFollowAdapter monolith;
  private final RemoteFollowAdapter remote;
  private final UserCommandPort commands;
  private final ExtractionProperties properties;
  private final ShadowComparator shadow;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingFollowPort(
      LocalFollowAdapter monolith,
      RemoteFollowAdapter remote,
      UserCommandPort commands,
      ExtractionProperties properties,
      ShadowComparator shadow,
      ReadAfterWriteMarker readAfterWrite) {
    this.monolith = monolith;
    this.remote = remote;
    this.commands = commands;
    this.properties = properties;
    this.shadow = shadow;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public boolean ownsFollowReads() {
    DomainRoute route = properties.getUser();
    return route.readsRemote() || route.shadows();
  }

  @Override
  public boolean isFollowing(String userId, String targetId) {
    return route(
        "isFollowing",
        () -> monolith.isFollowing(userId, targetId),
        () -> remote.isFollowing(userId, targetId),
        () -> false);
  }

  @Override
  public Set<String> followingAuthors(String userId, List<String> ids) {
    return route(
        "followingAuthors",
        () -> monolith.followingAuthors(userId, ids),
        () -> remote.followingAuthors(userId, ids),
        HashSet::new);
  }

  @Override
  public List<String> followedUsers(String userId) {
    return route(
        "followedUsers",
        () -> monolith.followedUsers(userId),
        () -> remote.followedUsers(userId),
        ArrayList::new);
  }

  @Override
  public void follow(String userId, String targetId) {
    commands.follow(new FollowRelation(userId, targetId));
  }

  @Override
  public void unfollow(String userId, String targetId) {
    commands.unfollow(new FollowRelation(userId, targetId));
  }

  private <T> T route(
      String op,
      java.util.function.Supplier<T> local,
      java.util.function.Supplier<T> extracted,
      java.util.function.Supplier<T> empty) {
    return RoutingUserQueryPort.route(
        properties, shadow, readAfterWrite, log, op, local, extracted, empty);
  }
}
