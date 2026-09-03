package io.spring.application.article.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code {"articles": [row...], "count": N}} envelope; {@code count} is absent for plain batches.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticlesDto {
  private List<ArticleRowDto> articles;
  private Integer count;
}
