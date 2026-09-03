package io.spring.user.core.user;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
  void save(User user);

  /** Only non-null, non-blank fields of the update are written. */
  void update(UserUpdate update);

  Optional<User> findById(String id);

  Optional<User> findByUsername(String username);

  Optional<User> findByEmail(String email);

  List<User> findByIds(List<String> ids);

  Optional<FollowRelation> findRelation(String userId, String targetId);

  /** Idempotent: inserts the pair only if absent. */
  void saveRelation(FollowRelation relation);

  /** Idempotent: removing an absent pair is a no-op. */
  void removeRelation(FollowRelation relation);

  /** Subset of ids that userId follows (mirrors the monolith's followingAuthors). */
  List<String> followingAuthors(String userId, List<String> ids);

  /** Everyone userId follows, in follows rowid order (mirrors followedUsers). */
  List<String> followedUsers(String userId);
}
