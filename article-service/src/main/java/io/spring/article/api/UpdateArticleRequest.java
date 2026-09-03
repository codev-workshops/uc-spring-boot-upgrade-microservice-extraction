package io.spring.article.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** PUT /internal/articles/{id} body; blank/missing fields are left untouched. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateArticleRequest {
  private String title;
  private String description;
  private String body;
}
