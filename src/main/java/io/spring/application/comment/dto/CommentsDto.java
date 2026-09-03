package io.spring.application.comment.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope of {@code GET /internal/articles/{articleId}/comments[/cursor]}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentsDto {
  private List<CommentRowDto> comments;
}
