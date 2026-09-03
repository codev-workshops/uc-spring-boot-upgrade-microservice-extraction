package io.spring.application.article;

import io.spring.core.article.Article;

/**
 * Write side of the Article domain. The monolith generates id, slug, timestamps and tag ids ({@code
 * Article}) so every store receives identical rows.
 */
public interface ArticleCommandPort {
  /** Inserts the row and its tags in one unit; idempotent on {@code id}. */
  void create(Article article);

  /** Applies {@code ArticleMapper.xml#update} semantics (blank fields skipped, no updated_at). */
  void update(Article article);

  void delete(Article article);
}
