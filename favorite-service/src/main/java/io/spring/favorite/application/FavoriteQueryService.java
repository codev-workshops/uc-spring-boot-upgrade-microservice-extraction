package io.spring.favorite.application;

import io.spring.favorite.application.data.ArticleFavoriteCount;
import io.spring.favorite.application.data.UserFavorites;
import io.spring.favorite.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class FavoriteQueryService {
  private final ArticleFavoritesReadService articleFavoritesReadService;

  public FavoriteQueryService(ArticleFavoritesReadService articleFavoritesReadService) {
    this.articleFavoritesReadService = articleFavoritesReadService;
  }

  /** Exactly one entry per requested id, in request order, count 0 when no favorite exists. */
  public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> articleIds) {
    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    Map<String, Integer> counts =
        articleFavoritesReadService.articlesFavoriteCount(distinct(articleIds)).stream()
            .collect(
                Collectors.toMap(
                    ArticleFavoriteCount::getArticleId, ArticleFavoriteCount::getCount));
    List<ArticleFavoriteCount> result = new ArrayList<>(articleIds.size());
    for (String id : articleIds) {
      result.add(new ArticleFavoriteCount(id, counts.getOrDefault(id, 0)));
    }
    return result;
  }

  /** The subset of the requested ids favorited by the user, in request order. */
  public UserFavorites userFavorites(String userId, List<String> articleIds) {
    if (articleIds.isEmpty()) {
      return new UserFavorites(userId, Collections.emptyList());
    }
    Set<String> favorited =
        new HashSet<>(articleFavoritesReadService.userFavorites(distinct(articleIds), userId));
    List<String> result =
        distinct(articleIds).stream().filter(favorited::contains).collect(Collectors.toList());
    return new UserFavorites(userId, result);
  }

  public UserFavorites articleIdsFavoritedBy(String userId) {
    return new UserFavorites(userId, articleFavoritesReadService.articleIdsFavoritedBy(userId));
  }

  private static List<String> distinct(List<String> ids) {
    return ids.stream().distinct().collect(Collectors.toList());
  }
}
