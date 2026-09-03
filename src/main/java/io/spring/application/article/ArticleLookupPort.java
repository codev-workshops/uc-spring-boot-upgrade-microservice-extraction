package io.spring.application.article;

import io.spring.core.article.Article;
import java.util.Optional;

/**
 * Cross-domain {@code slug -> Article} lookup used by the Favorite and Comment APIs for their 404
 * and {@code AuthorizationService} checks. Served by the authoritative store for article writes
 * (monolith table until {@code extraction.article.write=extracted}, article-service after).
 */
public interface ArticleLookupPort {
  Optional<Article> findById(String id);

  Optional<Article> findBySlug(String slug);
}
