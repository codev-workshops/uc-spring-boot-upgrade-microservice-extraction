package io.spring.infrastructure.extraction.user;

import java.time.Instant;
import lombok.Value;

/** A dual-write mirror call that failed against user-service and must be replayed. */
@Value
public class PendingUserMirrorOperation {
  public enum Kind {
    CREATE,
    UPDATE,
    FOLLOW,
    UNFOLLOW
  }

  Kind kind;
  String userId;
  String targetId;
  Instant failedAt;
  String error;
}
