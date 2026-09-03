package io.spring.application.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope of {@code GET /internal/comments/{id}} and {@code POST
 * /internal/articles/{articleId}/comments}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentDto {
  private CommentRowDto comment;
}
