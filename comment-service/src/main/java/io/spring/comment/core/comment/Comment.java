package io.spring.comment.core.comment;

import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/** Same shape as the monolith's io.spring.core.comment.Comment; ids are UUIDs. */
@Getter
@NoArgsConstructor
public class Comment {
  private String id;
  private String body;
  private String userId;
  private String articleId;
  private DateTime createdAt;

  public Comment(String id, String body, String userId, String articleId, DateTime createdAt) {
    this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
    this.body = body;
    this.userId = userId;
    this.articleId = articleId;
    this.createdAt = createdAt == null ? new DateTime() : createdAt;
  }
}
