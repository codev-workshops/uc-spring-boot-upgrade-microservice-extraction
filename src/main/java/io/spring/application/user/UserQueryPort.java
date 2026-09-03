package io.spring.application.user;

import io.spring.application.data.UserData;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the User domain (users rows without credentials). The implementation decides whether
 * the monolith SQL or user-service answers; callers only ask {@link #ownsUserReads()} to know
 * whether they should route through the port instead of the local read services.
 */
public interface UserQueryPort {
  Optional<UserData> findById(String id);

  Optional<UserData> findByUsername(String username);

  Optional<UserData> findByEmail(String email);

  /** Rows for the given ids, in no particular order; unknown ids are silently skipped. */
  List<UserData> findByIds(List<String> ids);

  /**
   * True when user reads must go through this port (extracted or shadow route). When false the
   * callers keep their local SQL untouched.
   */
  default boolean ownsUserReads() {
    return false;
  }
}
