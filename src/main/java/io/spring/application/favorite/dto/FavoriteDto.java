package io.spring.application.favorite.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response body of {@code PUT /internal/favorites/{articleId}/{userId}}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteDto {
  private String articleId;
  private String userId;
  private boolean favorited;
}
