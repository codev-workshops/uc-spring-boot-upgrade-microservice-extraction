package io.spring.tools.favoritesync;

import java.util.Objects;

/**
 * Natural key of {@code article_favorites}; ordering matches {@code order by article_id, user_id}.
 */
public final class FavoriteKey implements Comparable<FavoriteKey> {
  public final String articleId;
  public final String userId;

  public FavoriteKey(String articleId, String userId) {
    this.articleId = Objects.requireNonNull(articleId);
    this.userId = Objects.requireNonNull(userId);
  }

  @Override
  public int compareTo(FavoriteKey o) {
    int c = articleId.compareTo(o.articleId);
    return c != 0 ? c : userId.compareTo(o.userId);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FavoriteKey)) {
      return false;
    }
    FavoriteKey k = (FavoriteKey) o;
    return articleId.equals(k.articleId) && userId.equals(k.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(articleId, userId);
  }

  @Override
  public String toString() {
    return articleId + "|" + userId;
  }
}
