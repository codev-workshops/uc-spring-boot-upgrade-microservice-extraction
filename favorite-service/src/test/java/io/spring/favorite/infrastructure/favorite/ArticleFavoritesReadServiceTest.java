package io.spring.favorite.infrastructure.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.favorite.application.FavoriteQueryService;
import io.spring.favorite.application.data.ArticleFavoriteCount;
import io.spring.favorite.application.data.UserFavorites;
import io.spring.favorite.core.favorite.ArticleFavorite;
import io.spring.favorite.infrastructure.DbTestBase;
import io.spring.favorite.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.favorite.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({MyBatisArticleFavoriteRepository.class, FavoriteQueryService.class})
public class ArticleFavoritesReadServiceTest extends DbTestBase {
  @Autowired private MyBatisArticleFavoriteRepository repository;
  @Autowired private ArticleFavoritesReadService readService;
  @Autowired private FavoriteQueryService queryService;

  @BeforeEach
  public void setUp() {
    repository.save(new ArticleFavorite("article-1", "user-1"));
    repository.save(new ArticleFavorite("article-1", "user-2"));
    repository.save(new ArticleFavorite("article-2", "user-1"));
    repository.save(new ArticleFavorite("article-3", "user-2"));
  }

  @Test
  public void read_service_returns_rows_only_for_favorited_articles() {
    List<ArticleFavoriteCount> counts =
        readService.articlesFavoriteCount(Arrays.asList("article-1", "article-9"));
    assertEquals(1, counts.size());
    assertEquals("article-1", counts.get(0).getArticleId());
    assertEquals(2, counts.get(0).getCount());
  }

  @Test
  public void query_service_fills_zero_and_keeps_request_order() {
    List<ArticleFavoriteCount> counts =
        queryService.articlesFavoriteCount(Arrays.asList("article-9", "article-2", "article-1"));
    assertEquals(3, counts.size());
    assertEquals("article-9", counts.get(0).getArticleId());
    assertEquals(0, counts.get(0).getCount());
    assertEquals("article-2", counts.get(1).getArticleId());
    assertEquals(1, counts.get(1).getCount());
    assertEquals("article-1", counts.get(2).getArticleId());
    assertEquals(2, counts.get(2).getCount());
  }

  @Test
  public void query_service_short_circuits_empty_batch() {
    assertTrue(queryService.articlesFavoriteCount(Collections.emptyList()).isEmpty());
    assertTrue(
        queryService.userFavorites("user-1", Collections.emptyList()).getArticleIds().isEmpty());
  }

  @Test
  public void query_service_handles_500_id_batch() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      ids.add("article-" + i);
    }
    List<ArticleFavoriteCount> counts = queryService.articlesFavoriteCount(ids);
    assertEquals(500, counts.size());
    assertEquals(2, counts.get(1).getCount());
    assertEquals(0, counts.get(499).getCount());
  }

  @Test
  public void user_favorites_returns_favorited_subset_in_request_order() {
    UserFavorites favorites =
        queryService.userFavorites(
            "user-1", Arrays.asList("article-3", "article-2", "article-1", "article-9"));
    assertEquals("user-1", favorites.getUserId());
    assertEquals(Arrays.asList("article-2", "article-1"), favorites.getArticleIds());
  }

  @Test
  public void article_ids_favorited_by_are_sorted() {
    repository.save(new ArticleFavorite("article-0", "user-2"));
    UserFavorites favorites = queryService.articleIdsFavoritedBy("user-2");
    assertEquals(Arrays.asList("article-0", "article-1", "article-3"), favorites.getArticleIds());
    assertTrue(queryService.articleIdsFavoritedBy("nobody").getArticleIds().isEmpty());
  }
}
