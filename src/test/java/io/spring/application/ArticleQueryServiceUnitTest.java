package io.spring.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.CursorPager.Direction;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ProfileData;
import io.spring.application.readservice.ArticleFavoritesReadService;
import io.spring.application.readservice.ArticleReadService;
import io.spring.application.readservice.UserRelationshipQueryService;
import io.spring.core.user.User;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ArticleQueryServiceUnitTest {
  private ArticleReadService articleReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private ArticleFavoritesReadService articleFavoritesReadService;
  private ArticleQueryService articleQueryService;
  private User user;

  @BeforeEach
  public void setUp() {
    articleReadService = mock(ArticleReadService.class);
    userRelationshipQueryService = mock(UserRelationshipQueryService.class);
    articleFavoritesReadService = mock(ArticleFavoritesReadService.class);
    articleQueryService =
        new ArticleQueryService(
            articleReadService, userRelationshipQueryService, articleFavoritesReadService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
  }

  private ArticleData article(String id, String authorId) {
    DateTime time = new DateTime(2020, 1, 1, 0, 0);
    return new ArticleData(
        id,
        "slug-" + id,
        "title " + id,
        "desc",
        "body",
        false,
        0,
        time,
        time,
        Arrays.asList("java"),
        new ProfileData(authorId, "author-" + authorId, "", "", false));
  }

  private List<String> ids(List<ArticleData> articles) {
    List<String> result = new ArrayList<>();
    articles.forEach(a -> result.add(a.getId()));
    return result;
  }

  @Test
  public void should_return_empty_when_article_id_not_found() {
    when(articleReadService.findById(eq("missing"))).thenReturn(null);

    Assertions.assertFalse(articleQueryService.findById("missing", user).isPresent());
  }

  @Test
  public void should_fill_favorite_and_following_info_for_current_user_by_id() {
    when(articleReadService.findById(eq("a1"))).thenReturn(article("a1", "author1"));
    when(articleFavoritesReadService.isUserFavorite(eq(user.getId()), eq("a1"))).thenReturn(true);
    when(articleFavoritesReadService.articleFavoriteCount(eq("a1"))).thenReturn(3);
    when(userRelationshipQueryService.isUserFollowing(eq(user.getId()), eq("author1")))
        .thenReturn(true);

    Optional<ArticleData> result = articleQueryService.findById("a1", user);

    Assertions.assertTrue(result.isPresent());
    Assertions.assertTrue(result.get().isFavorited());
    Assertions.assertEquals(3, result.get().getFavoritesCount());
    Assertions.assertTrue(result.get().getProfileData().isFollowing());
  }

  @Test
  public void should_not_fill_extra_info_without_current_user() {
    when(articleReadService.findById(eq("a1"))).thenReturn(article("a1", "author1"));

    Optional<ArticleData> result = articleQueryService.findById("a1", null);

    Assertions.assertTrue(result.isPresent());
    Assertions.assertFalse(result.get().isFavorited());
    verify(articleFavoritesReadService, never()).isUserFavorite(any(), any());
  }

  @Test
  public void should_find_by_slug_with_and_without_user() {
    when(articleReadService.findBySlug(eq("slug-a1"))).thenReturn(article("a1", "author1"));
    when(articleReadService.findBySlug(eq("missing"))).thenReturn(null);
    when(articleFavoritesReadService.articleFavoriteCount(eq("a1"))).thenReturn(5);

    Assertions.assertFalse(articleQueryService.findBySlug("missing", user).isPresent());
    Assertions.assertEquals(
        0, articleQueryService.findBySlug("slug-a1", null).get().getFavoritesCount());
    Assertions.assertEquals(
        5, articleQueryService.findBySlug("slug-a1", user).get().getFavoritesCount());
  }

  @Test
  public void should_return_empty_pager_when_no_recent_articles() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.NEXT);
    when(articleReadService.findArticlesWithCursor(any(), any(), any(), eq(page)))
        .thenReturn(new ArrayList<>());

    CursorPager<ArticleData> pager =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, user);

    Assertions.assertTrue(pager.getData().isEmpty());
    Assertions.assertFalse(pager.hasNext());
    verify(articleReadService, never()).findArticles(anyList());
  }

  @Test
  public void should_remove_extra_item_and_mark_has_next() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.NEXT);
    when(articleReadService.findArticlesWithCursor(eq("java"), any(), any(), eq(page)))
        .thenReturn(new ArrayList<>(Arrays.asList("a1", "a2", "a3")));
    when(articleReadService.findArticles(eq(Arrays.asList("a1", "a2"))))
        .thenReturn(new ArrayList<>(Arrays.asList(article("a1", "x"), article("a2", "y"))));
    stubFavoriteCounts(Collections.singletonMap("a1", 4));
    when(articleFavoritesReadService.userFavorites(anyList(), eq(user)))
        .thenReturn(new HashSet<>(Collections.singletonList("a2")));
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(new HashSet<>(Collections.singletonList("x")));

    CursorPager<ArticleData> pager =
        articleQueryService.findRecentArticlesWithCursor("java", null, null, page, user);

    Assertions.assertEquals(Arrays.asList("a1", "a2"), ids(pager.getData()));
    Assertions.assertTrue(pager.hasNext());
    Assertions.assertFalse(pager.hasPrevious());
    ArticleData a1 = pager.getData().get(0);
    ArticleData a2 = pager.getData().get(1);
    Assertions.assertEquals(4, a1.getFavoritesCount());
    Assertions.assertFalse(a1.isFavorited());
    Assertions.assertTrue(a1.getProfileData().isFollowing());
    Assertions.assertTrue(a2.isFavorited());
    Assertions.assertFalse(a2.getProfileData().isFollowing());
  }

  @Test
  public void should_reverse_ids_for_previous_page() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.PREV);
    when(articleReadService.findArticlesWithCursor(any(), any(), any(), eq(page)))
        .thenReturn(new ArrayList<>(Arrays.asList("a3", "a2")));
    when(articleReadService.findArticles(eq(Arrays.asList("a2", "a3"))))
        .thenReturn(new ArrayList<>(Arrays.asList(article("a2", "x"), article("a3", "x"))));
    stubFavoriteCounts(Collections.emptyMap());

    CursorPager<ArticleData> pager =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, null);

    Assertions.assertEquals(Arrays.asList("a2", "a3"), ids(pager.getData()));
    Assertions.assertFalse(pager.hasPrevious());
    verify(articleFavoritesReadService, never()).userFavorites(anyList(), any());
  }

  @Test
  public void should_return_empty_feed_when_user_follows_nobody() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.NEXT);
    when(userRelationshipQueryService.followedUsers(eq(user.getId())))
        .thenReturn(new ArrayList<>());

    Assertions.assertTrue(
        articleQueryService.findUserFeedWithCursor(user, page).getData().isEmpty());
    Assertions.assertEquals(0, articleQueryService.findUserFeed(user, new Page()).getCount());
  }

  @Test
  public void should_remove_extra_item_and_reverse_previous_feed_page() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.PREV);
    List<String> followed = Collections.singletonList("x");
    when(userRelationshipQueryService.followedUsers(eq(user.getId()))).thenReturn(followed);
    when(articleReadService.findArticlesOfAuthorsWithCursor(eq(followed), eq(page)))
        .thenReturn(
            new ArrayList<>(
                Arrays.asList(article("a3", "x"), article("a2", "x"), article("a1", "x"))));
    stubFavoriteCounts(Collections.emptyMap());
    when(articleFavoritesReadService.userFavorites(anyList(), eq(user)))
        .thenReturn(new HashSet<>());
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(new HashSet<>(followed));

    CursorPager<ArticleData> pager = articleQueryService.findUserFeedWithCursor(user, page);

    Assertions.assertEquals(Arrays.asList("a2", "a3"), ids(pager.getData()));
    Assertions.assertTrue(pager.hasPrevious());
    Assertions.assertFalse(pager.hasNext());
    Assertions.assertTrue(pager.getData().get(0).getProfileData().isFollowing());
  }

  @Test
  public void should_return_feed_page_with_count() {
    Page page = new Page(0, 10);
    List<String> followed = Collections.singletonList("x");
    when(userRelationshipQueryService.followedUsers(eq(user.getId()))).thenReturn(followed);
    when(articleReadService.findArticlesOfAuthors(eq(followed), eq(page)))
        .thenReturn(new ArrayList<>(Collections.singletonList(article("a1", "x"))));
    when(articleReadService.countFeedSize(eq(followed))).thenReturn(7);
    stubFavoriteCounts(Collections.emptyMap());
    when(articleFavoritesReadService.userFavorites(anyList(), eq(user)))
        .thenReturn(new HashSet<>());
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(new HashSet<>());

    ArticleDataList list = articleQueryService.findUserFeed(user, page);

    Assertions.assertEquals(7, list.getCount());
    Assertions.assertEquals(Collections.singletonList("a1"), ids(list.getArticleDatas()));
  }

  @Test
  public void should_return_recent_articles_with_total_count() {
    Page page = new Page(0, 10);
    when(articleReadService.queryArticles(eq("java"), isNullString(), isNullString(), eq(page)))
        .thenReturn(new ArrayList<>(Collections.singletonList("a1")));
    when(articleReadService.countArticle(eq("java"), isNullString(), isNullString())).thenReturn(1);
    when(articleReadService.findArticles(eq(Collections.singletonList("a1"))))
        .thenReturn(new ArrayList<>(Collections.singletonList(article("a1", "x"))));
    stubFavoriteCounts(Collections.emptyMap());

    ArticleDataList list = articleQueryService.findRecentArticles("java", null, null, page, null);

    Assertions.assertEquals(1, list.getCount());
    Assertions.assertEquals(Collections.singletonList("a1"), ids(list.getArticleDatas()));
  }

  @Test
  public void should_return_empty_recent_articles_with_count() {
    Page page = new Page(0, 10);
    when(articleReadService.queryArticles(any(), any(), any(), eq(page)))
        .thenReturn(new ArrayList<>());
    when(articleReadService.countArticle(any(), any(), any())).thenReturn(0);

    ArticleDataList list = articleQueryService.findRecentArticles(null, null, null, page, user);

    Assertions.assertTrue(list.getArticleDatas().isEmpty());
    Assertions.assertEquals(0, list.getCount());
    verify(articleReadService, never()).findArticles(anyList());
  }

  private void stubFavoriteCounts(Map<String, Integer> counts) {
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenAnswer(
            inv -> {
              List<String> articleIds = inv.getArgument(0);
              List<ArticleFavoriteCount> result = new ArrayList<>();
              articleIds.forEach(
                  id -> result.add(new ArticleFavoriteCount(id, counts.getOrDefault(id, 0))));
              return result;
            });
  }

  private static String isNullString() {
    return org.mockito.ArgumentMatchers.isNull();
  }
}
