package io.spring.application.favorite.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body of {@code POST /internal/favorites/counts}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleIdsRequest {
  private List<String> articleIds;
}
