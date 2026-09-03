package io.spring.infrastructure.extraction.favorite;

import io.spring.application.favorite.FavoriteCommandPort;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link ArticleFavoriteRepository} the REST and GraphQL favorite endpoints see. Writes go
 * through the routing {@link FavoriteCommandPort}; {@link #find} stays on the monolith table while
 * it is authoritative and moves to the favorite port once {@code write=extracted}.
 */
@Primary
@Repository
public class RoutingArticleFavoriteRepository implements ArticleFavoriteRepository {
  private final MyBatisArticleFavoriteRepository monolith;
  private final FavoriteCommandPort commands;
  private final FavoriteQueryPort queries;
  private final ExtractionProperties properties;

  public RoutingArticleFavoriteRepository(
      MyBatisArticleFavoriteRepository monolith,
      FavoriteCommandPort commands,
      FavoriteQueryPort queries,
      ExtractionProperties properties) {
    this.monolith = monolith;
    this.commands = commands;
    this.queries = queries;
    this.properties = properties;
  }

  @Override
  public void save(ArticleFavorite articleFavorite) {
    commands.favorite(articleFavorite.getArticleId(), articleFavorite.getUserId());
  }

  @Override
  public Optional<ArticleFavorite> find(String articleId, String userId) {
    if (properties.getFavorite().monolithAuthoritative()) {
      return monolith.find(articleId, userId);
    }
    return queries.isUserFavorite(userId, articleId)
        ? Optional.of(new ArticleFavorite(articleId, userId))
        : Optional.empty();
  }

  @Override
  public void remove(ArticleFavorite favorite) {
    commands.unfavorite(favorite.getArticleId(), favorite.getUserId());
  }
}
