package io.spring.application.user;

import java.util.List;
import java.util.Set;

/** Follow relation reads and writes ({@code follows(user_id, follow_id)}). */
public interface FollowPort {
  boolean isFollowing(String userId, String targetId);

  /** Subset of {@code ids} that {@code userId} follows. */
  Set<String> followingAuthors(String userId, List<String> ids);

  /** Ids of every user that {@code userId} follows. */
  List<String> followedUsers(String userId);

  /** Idempotent. */
  void follow(String userId, String targetId);

  /** Idempotent. */
  void unfollow(String userId, String targetId);

  /** See {@link UserQueryPort#ownsUserReads()}. */
  default boolean ownsFollowReads() {
    return false;
  }
}
