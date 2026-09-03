package io.spring.article.core.article;

import java.util.Optional;

public interface ArticleRepository {
  /** Inserts the articles row only; tags are attached by the caller in the same transaction. */
  void insert(Article article);

  Optional<Article> findById(String id);

  Optional<Article> findBySlug(String slug);

  /**
   * Mirrors ArticleMapper.xml#update: blank title/description/body are skipped, slug is written
   * only together with a non-blank title, and updated_at is never written.
   */
  void update(String id, String title, String slug, String description, String body);

  /** Deletes only the articles row (article_tags, comments, favorites are untouched). */
  void remove(String id);
}
