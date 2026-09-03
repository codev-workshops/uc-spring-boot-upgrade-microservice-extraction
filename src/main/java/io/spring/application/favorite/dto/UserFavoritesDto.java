package io.spring.application.favorite.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body of {@code POST /internal/favorites/query} and {@code GET
 * /internal/favorites/by-user/{userId}/article-ids}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserFavoritesDto {
  private String userId;
  private List<String> articleIds = new ArrayList<>();
}
