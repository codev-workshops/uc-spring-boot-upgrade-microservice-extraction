package io.spring.infrastructure.extraction.tag;

import java.time.Instant;
import java.util.List;
import lombok.Value;

/** A tag set that was written by the monolith but could not be mirrored to article-service. */
@Value
public class PendingTagMirrorOperation {
  String articleId;
  List<String> tagNames;
  Instant failedAt;
  String error;
}
