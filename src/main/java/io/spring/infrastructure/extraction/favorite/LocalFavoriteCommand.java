package io.spring.infrastructure.extraction.favorite;

import io.spring.application.favorite.FavoriteCommandPort;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import org.springframework.stereotype.Component;

/** Writes to the monolith {@code article_favorites} table. */
@Component
public class LocalFavoriteCommand implements FavoriteCommandPort {
  private final MyBatisArticleFavoriteRepository repository;

  public LocalFavoriteCommand(MyBatisArticleFavoriteRepository repository) {
    this.repository = repository;
  }

  @Override
  public void favorite(String articleId, String userId) {
    repository.save(new ArticleFavorite(articleId, userId));
  }

  @Override
  public void unfavorite(String articleId, String userId) {
    repository.find(articleId, userId).ifPresent(repository::remove);
  }
}
