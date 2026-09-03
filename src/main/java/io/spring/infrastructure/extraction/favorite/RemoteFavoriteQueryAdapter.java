package io.spring.infrastructure.extraction.favorite;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.application.favorite.dto.FavoriteCountDto;
import io.spring.core.user.User;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link FavoriteQueryPort} backed by favorite-service. Empty id lists never reach the wire, large
 * lists are chunked, and every requested id gets a count entry ({@code 0} when the service has
 * none) so callers can keep unboxing {@code favoritesCount} to a primitive.
 */
@Component
public class RemoteFavoriteQueryAdapter implements FavoriteQueryPort {
  static final int MAX_BATCH = 500;

  private final FavoriteServiceClient client;

  public RemoteFavoriteQueryAdapter(FavoriteServiceClient client) {
    this.client = client;
  }

  @Override
  public boolean isUserFavorite(String userId, String articleId) {
    return client
        .userFavorites(userId, Collections.singletonList(articleId))
        .getArticleIds()
        .contains(articleId);
  }

  @Override
  public int articleFavoriteCount(String articleId) {
    return client.counts(Collections.singletonList(articleId)).stream()
        .filter(dto -> articleId.equals(dto.getArticleId()))
        .map(FavoriteCountDto::getCount)
        .findFirst()
        .orElse(0);
  }

  @Override
  public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids) {
    if (ids.isEmpty()) {
      return new ArrayList<>();
    }
    Map<String, Integer> counts = new HashMap<>();
    for (List<String> chunk : chunks(ids)) {
      client.counts(chunk).forEach(dto -> counts.put(dto.getArticleId(), dto.getCount()));
    }
    return zeroFill(ids, counts);
  }

  @Override
  public Set<String> userFavorites(List<String> ids, User currentUser) {
    Set<String> favorited = new HashSet<>();
    if (ids.isEmpty()) {
      return favorited;
    }
    for (List<String> chunk : chunks(ids)) {
      favorited.addAll(client.userFavorites(currentUser.getId(), chunk).getArticleIds());
    }
    return favorited;
  }

  @Override
  public List<String> articleIdsFavoritedBy(String userId) {
    return new ArrayList<>(client.articleIdsFavoritedBy(userId).getArticleIds());
  }

  @Override
  public boolean ownsFavoritedByFilter() {
    return true;
  }

  static List<ArticleFavoriteCount> zeroFill(List<String> ids, Map<String, Integer> counts) {
    List<ArticleFavoriteCount> result = new ArrayList<>(ids.size());
    Set<String> seen = new HashSet<>();
    for (String id : ids) {
      if (seen.add(id)) {
        result.add(new ArticleFavoriteCount(id, counts.getOrDefault(id, 0)));
      }
    }
    return result;
  }

  static List<List<String>> chunks(List<String> ids) {
    List<List<String>> chunks = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += MAX_BATCH) {
      chunks.add(new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + MAX_BATCH))));
    }
    return chunks;
  }
}
