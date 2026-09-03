package io.spring.application.article.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code {"article": row}} envelope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDto {
  private ArticleRowDto article;
}
