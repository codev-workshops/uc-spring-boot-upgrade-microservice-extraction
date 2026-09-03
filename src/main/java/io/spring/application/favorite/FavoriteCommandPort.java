package io.spring.application.favorite;

/** Write side of the Favorite domain. Both operations are idempotent. */
public interface FavoriteCommandPort {
  void favorite(String articleId, String userId);

  void unfavorite(String articleId, String userId);
}
