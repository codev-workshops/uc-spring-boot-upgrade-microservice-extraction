package io.spring.application.favorite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spring.application.ArticleQueryService;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the favorites-count mapping of ArticleQueryService.setFavoriteCount, which looks the count
 * up in a map built from the read service. Today the SQL always returns a row per existing article,
 * so the value is 0 rather than null; when a row is missing the primitive favoritesCount field
 * cannot be set and the read fails. The extracted favorite service must keep returning a row (with
 * count 0) for every article id it knows about.
 */
public class FavoriteCountContractTest {
  private final ArticleReadService articleReadService = mock(ArticleReadService.class);
  private final UserRelationshipQueryService userRelationshipQueryService =
      mock(UserRelationshipQueryService.class);
  private final ArticleFavoritesReadService articleFavoritesReadService =
      mock(ArticleFavoritesReadService.class);
  private final ArticleQueryService queryService =
      new ArticleQueryService(
          articleReadService, userRelationshipQueryService, articleFavoritesReadService);

  private final User user = new User("reader@test.com", "reader", "123", "", "");

  @Test
  public void should_map_a_zero_count_row_to_zero() {
    ArticleData articleData = articleData();
    stubArticles(articleData);
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(Collections.singletonList(new ArticleFavoriteCount(articleData.getId(), 0)));
    when(articleFavoritesReadService.userFavorites(anyList(), any())).thenReturn(new HashSet<>());

    ArticleDataList result = queryService.findRecentArticles(null, null, null, new Page(), user);

    Assertions.assertEquals(0, result.getArticleDatas().get(0).getFavoritesCount());
    Assertions.assertFalse(result.getArticleDatas().get(0).isFavorited());
  }

  @Test
  public void should_fail_when_the_count_row_is_missing_for_an_article() {
    ArticleData articleData = articleData();
    stubArticles(articleData);
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(new ArrayList<>());
    when(articleFavoritesReadService.userFavorites(anyList(), any())).thenReturn(new HashSet<>());

    Assertions.assertThrows(
        NullPointerException.class,
        () -> queryService.findRecentArticles(null, null, null, new Page(), user));
  }

  private void stubArticles(ArticleData articleData) {
    List<String> ids = Collections.singletonList(articleData.getId());
    when(articleReadService.queryArticles(any(), any(), any(), any())).thenReturn(ids);
    when(articleReadService.countArticle(any(), any(), any())).thenReturn(1);
    when(articleReadService.findArticles(ids))
        .thenReturn(new ArrayList<>(Collections.singletonList(articleData)));
    when(userRelationshipQueryService.followingAuthors(any(), anyList()))
        .thenReturn(new HashSet<>());
  }

  private ArticleData articleData() {
    return io.spring.TestHelper.articleDataFixture("count", user);
  }

  @Test
  public void should_keep_favorites_count_of_every_article_in_a_batch() {
    ArticleData first = io.spring.TestHelper.articleDataFixture("one", user);
    ArticleData second = io.spring.TestHelper.articleDataFixture("two", user);
    List<String> ids = Arrays.asList(first.getId(), second.getId());
    when(articleReadService.queryArticles(any(), any(), any(), any())).thenReturn(ids);
    when(articleReadService.countArticle(any(), any(), any())).thenReturn(2);
    when(articleReadService.findArticles(ids))
        .thenReturn(new ArrayList<>(Arrays.asList(first, second)));
    when(userRelationshipQueryService.followingAuthors(any(), anyList()))
        .thenReturn(new HashSet<>());
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount(first.getId(), 3),
                new ArticleFavoriteCount(second.getId(), 0)));
    when(articleFavoritesReadService.userFavorites(anyList(), any()))
        .thenReturn(new HashSet<>(Collections.singletonList(first.getId())));

    ArticleDataList result = queryService.findRecentArticles(null, null, null, new Page(), user);

    Assertions.assertEquals(3, result.getArticleDatas().get(0).getFavoritesCount());
    Assertions.assertTrue(result.getArticleDatas().get(0).isFavorited());
    Assertions.assertEquals(0, result.getArticleDatas().get(1).getFavoritesCount());
    Assertions.assertFalse(result.getArticleDatas().get(1).isFavorited());
  }
}
