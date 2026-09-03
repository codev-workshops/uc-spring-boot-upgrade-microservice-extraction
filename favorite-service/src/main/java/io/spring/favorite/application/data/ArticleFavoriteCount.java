package io.spring.favorite.application.data;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;

@Getter
@JsonPropertyOrder({"articleId", "count"})
public class ArticleFavoriteCount {
  private final String articleId;
  private final int count;

  public ArticleFavoriteCount(String articleId, Integer count) {
    this.articleId = articleId;
    this.count = count == null ? 0 : count;
  }
}
