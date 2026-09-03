package io.spring.application.favorite;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.core.user.User;
import java.util.List;
import java.util.Set;

/**
 * Read side of the Favorite domain as seen by the monolith. Implemented by the MyBatis read service
 * (monolith table), by the remote adapter (favorite-service) and by the routing port that picks one
 * of them per call according to {@code extraction.favorite.*}.
 */
public interface FavoriteQueryPort {
  boolean isUserFavorite(String userId, String articleId);

  int articleFavoriteCount(String articleId);

  /** Must return exactly one entry per requested id ({@code 0} when never favorited). */
  List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids);

  Set<String> userFavorites(List<String> ids, User currentUser);

  /** Ids of every article the given user has favorited; used by the {@code favoritedBy} filter. */
  List<String> articleIdsFavoritedBy(String userId);

  /**
   * Whether the {@code favoritedBy} article filter must be resolved through this port (id list)
   * instead of the SQL join in {@code ArticleReadService.xml}.
   */
  default boolean ownsFavoritedByFilter() {
    return false;
  }
}
