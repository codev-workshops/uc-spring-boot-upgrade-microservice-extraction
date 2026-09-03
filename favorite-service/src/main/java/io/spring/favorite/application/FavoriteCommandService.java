package io.spring.favorite.application;

import io.spring.favorite.application.data.FavoriteData;
import io.spring.favorite.core.favorite.ArticleFavorite;
import io.spring.favorite.core.favorite.ArticleFavoriteRepository;
import org.springframework.stereotype.Service;

@Service
public class FavoriteCommandService {
  private final ArticleFavoriteRepository articleFavoriteRepository;

  public FavoriteCommandService(ArticleFavoriteRepository articleFavoriteRepository) {
    this.articleFavoriteRepository = articleFavoriteRepository;
  }

  /** Idempotent: favoriting twice leaves a single row and still reports favorited=true. */
  public FavoriteData favorite(String articleId, String userId) {
    articleFavoriteRepository.save(new ArticleFavorite(articleId, userId));
    return new FavoriteData(articleId, userId, true);
  }

  /** Idempotent: removing a favorite that does not exist is a no-op. */
  public void unfavorite(String articleId, String userId) {
    articleFavoriteRepository.remove(new ArticleFavorite(articleId, userId));
  }
}
