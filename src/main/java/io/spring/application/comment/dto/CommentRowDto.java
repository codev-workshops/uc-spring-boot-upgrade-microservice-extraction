package io.spring.application.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Raw comment row of the comment-service internal API (no profile data). {@code createdAt} and
 * {@code updatedAt} are ISO-8601 strings; {@code updatedAt} is copied from {@code created_at}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentRowDto {
  private String id;
  private String body;
  private String articleId;
  private String userId;
  private String createdAt;
  private String updatedAt;
}
