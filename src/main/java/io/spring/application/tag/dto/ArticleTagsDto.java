package io.spring.application.tag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope of {@code GET /internal/articles/tags?articleIds=}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagsDto {
  private List<ArticleTagsRowDto> articleTags;
}
