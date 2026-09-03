package io.spring.application.article.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body of {@code PUT /internal/articles/{id}}; blank fields are skipped by the service
 * exactly like {@code ArticleMapper.xml#update}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateArticleRequest {
  private String title;
  private String description;
  private String body;
  private String slug;
}
