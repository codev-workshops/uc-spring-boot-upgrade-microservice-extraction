package io.spring.infrastructure.extraction.article;

import java.time.Instant;
import lombok.Value;

/** An article write that succeeded in the monolith but could not be mirrored to article-service. */
@Value
public class PendingArticleMirrorOperation {
  public enum Kind {
    CREATE,
    UPDATE,
    DELETE
  }

  Kind kind;
  String articleId;
  Instant failedAt;
  String error;
}
