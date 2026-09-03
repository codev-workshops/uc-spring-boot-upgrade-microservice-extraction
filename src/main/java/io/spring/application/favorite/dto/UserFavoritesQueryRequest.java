package io.spring.application.favorite.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body of {@code POST /internal/favorites/query}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserFavoritesQueryRequest {
  private String userId;
  private List<String> articleIds;
}
