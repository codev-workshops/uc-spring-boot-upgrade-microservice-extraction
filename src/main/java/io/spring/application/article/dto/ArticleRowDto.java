package io.spring.application.article.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Article row of the article-service internal API (no profile data, no favorites). Timestamps are
 * ISO-8601 strings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleRowDto {
  private String id;
  private String slug;
  private String title;
  private String description;
  private String body;
  private String userId;
  private String createdAt;
  private String updatedAt;
  private List<String> tagList;
}
