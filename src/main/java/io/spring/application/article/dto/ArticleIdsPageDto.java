package io.spring.application.article.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code {"articleIds": [...], "count": N}} envelope; {@code count} is absent for cursor pages. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleIdsPageDto {
  private List<String> articleIds;
  private Integer count;
}
