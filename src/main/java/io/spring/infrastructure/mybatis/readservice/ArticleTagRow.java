package io.spring.infrastructure.mybatis.readservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One {@code article_tags} row joined to its tag name. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagRow {
  private String articleId;
  private String name;
}
