package io.spring.infrastructure.extraction.comment;

import java.time.Instant;
import lombok.Value;

/** A comment write that succeeded in the monolith but could not be mirrored to comment-service. */
@Value
public class PendingCommentMirrorOperation {
  public enum Kind {
    CREATE,
    DELETE
  }

  Kind kind;
  String articleId;
  String commentId;
  Instant failedAt;
  String error;
}
