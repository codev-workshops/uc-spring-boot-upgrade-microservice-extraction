package io.spring.application.tag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One article's tag names as returned by article-service ({@code GET /internal/articles/tags} rows
 * and the body of {@code PUT /internal/articles/{articleId}/tags}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagsRowDto {
  private String articleId;
  private List<String> tagList;
}
