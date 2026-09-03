package io.spring.infrastructure.extraction.favorite;

import java.time.Instant;
import lombok.Value;

/**
 * A favorite write that succeeded in the monolith but could not be mirrored to favorite-service.
 */
@Value
public class PendingMirrorOperation {
  public enum Kind {
    FAVORITE,
    UNFAVORITE
  }

  Kind kind;
  String articleId;
  String userId;
  Instant failedAt;
  String error;
}
