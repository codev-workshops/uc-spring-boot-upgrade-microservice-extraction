package io.spring.favorite.application.data;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonPropertyOrder({"articleId", "userId", "favorited"})
public class FavoriteData {
  private final String articleId;
  private final String userId;
  private final boolean favorited;
}
