package io.spring.comment.application.data;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/** Raw comment row (no author profile); updatedAt == createdAt like the monolith's CommentData. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"id", "body", "articleId", "userId", "createdAt", "updatedAt"})
public class CommentData {
  private String id;
  private String body;
  private String articleId;
  private String userId;
  private DateTime createdAt;
  private DateTime updatedAt;
}
