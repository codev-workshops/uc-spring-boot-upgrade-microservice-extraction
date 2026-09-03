package io.spring.article.application.data;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One (article_id, tag name) join row read from article_tags. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleTagRow {
  private String articleId;
  private String tagName;
}
