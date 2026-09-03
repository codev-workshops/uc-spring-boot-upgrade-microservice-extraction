package io.spring.application.tag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope of {@code GET /internal/tags/{name}/article-ids}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleIdsDto {
  private List<String> articleIds;
}
