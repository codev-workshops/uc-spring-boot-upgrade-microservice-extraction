package io.spring.application.user;

import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;

/**
 * Write side of the User domain. {@code user.getPassword()} carries the BCrypt hash produced by the
 * monolith (or is blank for rows loaded from user-service, which then leaves the stored hash
 * untouched).
 */
public interface UserCommandPort {
  void create(User user);

  void update(User user);

  void follow(FollowRelation relation);

  void unfollow(FollowRelation relation);
}
