package io.spring.application.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ProfileData;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ArticleQueryServiceUnitTest {
  @Mock private ArticleReadService articleReadService;
  @Mock private UserRelationshipQueryService userRelationshipQueryService;
  @Mock private ArticleFavoritesReadService articleFavoritesReadService;

  private ArticleQueryService articleQueryService;
  private User currentUser;

  @BeforeEach
  public void setUp() {
    articleQueryService =
        new ArticleQueryService(
            articleReadService, userRelationshipQueryService, articleFavoritesReadService);
    currentUser = new User("current@example.com", "current", "password", "", "");
  }

  @Test
  public void findByIdReturnsEmptyWhenArticleIsMissing() {
    when(articleReadService.findById("missing")).thenReturn(null);

    assertTrue(articleQueryService.findById("missing", currentUser).isEmpty());
  }

  @Test
  public void findByIdDoesNotFillExtraInfoWithoutUser() {
    ArticleData article = article("article-id", "author-id");
    when(articleReadService.findById("article-id")).thenReturn(article);

    assertTrue(articleQueryService.findById("article-id", null).isPresent());
    verifyNoInteractions(articleFavoritesReadService, userRelationshipQueryService);
  }

  @Test
  public void findByIdFillsExtraInfoForUser() {
    ArticleData article = article("article-id", "author-id");
    when(articleReadService.findById("article-id")).thenReturn(article);
    when(articleFavoritesReadService.isUserFavorite(currentUser.getId(), "article-id"))
        .thenReturn(true);
    when(articleFavoritesReadService.articleFavoriteCount("article-id")).thenReturn(3);
    when(userRelationshipQueryService.isUserFollowing(currentUser.getId(), "author-id"))
        .thenReturn(true);

    ArticleData result = articleQueryService.findById("article-id", currentUser).orElseThrow();

    assertTrue(result.isFavorited());
    assertEquals(3, result.getFavoritesCount());
    assertTrue(result.getProfileData().isFollowing());
  }

  @Test
  public void findBySlugReturnsEmptyWhenArticleIsMissing() {
    when(articleReadService.findBySlug("missing")).thenReturn(null);

    assertTrue(articleQueryService.findBySlug("missing", currentUser).isEmpty());
  }

  @Test
  public void findBySlugDoesNotFillExtraInfoWithoutUser() {
    ArticleData article = article("article-id", "author-id");
    when(articleReadService.findBySlug("slug")).thenReturn(article);

    assertTrue(articleQueryService.findBySlug("slug", null).isPresent());
    verifyNoInteractions(articleFavoritesReadService, userRelationshipQueryService);
  }

  @Test
  public void findBySlugFillsExtraInfoForUser() {
    ArticleData article = article("article-id", "author-id");
    when(articleReadService.findBySlug("slug")).thenReturn(article);
    when(articleFavoritesReadService.isUserFavorite(currentUser.getId(), "article-id"))
        .thenReturn(true);
    when(articleFavoritesReadService.articleFavoriteCount("article-id")).thenReturn(3);
    when(userRelationshipQueryService.isUserFollowing(currentUser.getId(), "author-id"))
        .thenReturn(true);

    ArticleData result = articleQueryService.findBySlug("slug", currentUser).orElseThrow();

    assertTrue(result.isFavorited());
    assertEquals(3, result.getFavoritesCount());
    assertTrue(result.getProfileData().isFollowing());
  }

  @Test
  public void recentArticlesWithEmptyIdsReturnsEmptyPager() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    when(articleReadService.findArticlesWithCursor(null, null, null, page))
        .thenReturn(new ArrayList<>());

    CursorPager<ArticleData> result =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, currentUser);

    assertTrue(result.getData().isEmpty());
    assertFalse(result.hasNext());
    assertFalse(result.hasPrevious());
    verify(articleReadService, never()).findArticles(anyList());
  }

  @Test
  public void recentArticlesWithExactlyLimitIdsHasNoNextPage() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> ids = Arrays.asList("article-1", "article-2");
    List<ArticleData> articles =
        Arrays.asList(article("article-1", "author-1"), article("article-2", "author-2"));
    when(articleReadService.findArticlesWithCursor(null, null, null, page)).thenReturn(ids);
    when(articleReadService.findArticles(ids)).thenReturn(articles);
    when(articleFavoritesReadService.articlesFavoriteCount(ids))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 0),
                new ArticleFavoriteCount("article-2", 0)));

    CursorPager<ArticleData> result =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, null);

    assertEquals(articles, result.getData());
    assertFalse(result.hasNext());
    verify(articleReadService).findArticles(ids);
  }

  @Test
  public void recentArticlesWithExtraIdHasNextPageAndTrimsQuery() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> ids = new ArrayList<>(Arrays.asList("article-1", "article-2", "extra"));
    List<ArticleData> articles =
        Arrays.asList(article("article-1", "author-1"), article("article-2", "author-2"));
    when(articleReadService.findArticlesWithCursor(null, null, null, page)).thenReturn(ids);
    when(articleReadService.findArticles(anyList())).thenReturn(articles);
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 0),
                new ArticleFavoriteCount("article-2", 0)));

    CursorPager<ArticleData> result =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, null);

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(articleReadService).findArticles(captor.capture());
    assertEquals(Arrays.asList("article-1", "article-2"), captor.getValue());
    assertTrue(result.hasNext());
    assertFalse(result.hasPrevious());
  }

  @Test
  public void previousRecentArticlesTrimAndReverseQuery() {
    CursorPageParameter<DateTime> page = page(2, Direction.PREV);
    List<String> ids = new ArrayList<>(Arrays.asList("article-1", "article-2", "extra"));
    List<ArticleData> articles =
        Arrays.asList(article("article-2", "author-2"), article("article-1", "author-1"));
    when(articleReadService.findArticlesWithCursor(null, null, null, page)).thenReturn(ids);
    when(articleReadService.findArticles(anyList())).thenReturn(articles);
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 0),
                new ArticleFavoriteCount("article-2", 0)));

    CursorPager<ArticleData> result =
        articleQueryService.findRecentArticlesWithCursor(null, null, null, page, null);

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(articleReadService).findArticles(captor.capture());
    assertEquals(Arrays.asList("article-2", "article-1"), captor.getValue());
    assertTrue(result.hasPrevious());
    assertFalse(result.hasNext());
  }

  @Test
  public void recentArticlesWithoutUserOnlyFillFavoriteCounts() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> ids = Arrays.asList("article-1", "article-2");
    List<ArticleData> articles =
        Arrays.asList(article("article-1", "author-1"), article("article-2", "author-2"));
    when(articleReadService.findArticlesWithCursor(null, null, null, page)).thenReturn(ids);
    when(articleReadService.findArticles(ids)).thenReturn(articles);
    when(articleFavoritesReadService.articlesFavoriteCount(ids))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 4),
                new ArticleFavoriteCount("article-2", 0)));

    articleQueryService.findRecentArticlesWithCursor(null, null, null, page, null);

    verify(articleFavoritesReadService).articlesFavoriteCount(ids);
    verify(articleFavoritesReadService, never()).userFavorites(anyList(), any());
    verifyNoInteractions(userRelationshipQueryService);
    assertEquals(4, articles.get(0).getFavoritesCount());
  }

  @Test
  public void recentArticlesFillFollowingAndFavoritesForUser() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> ids = Arrays.asList("article-1", "article-2");
    List<ArticleData> articles =
        Arrays.asList(article("article-1", "author-1"), article("article-2", "author-2"));
    when(articleReadService.findArticlesWithCursor(null, null, null, page)).thenReturn(ids);
    when(articleReadService.findArticles(ids)).thenReturn(articles);
    when(articleFavoritesReadService.articlesFavoriteCount(ids))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 4),
                new ArticleFavoriteCount("article-2", 1)));
    when(articleFavoritesReadService.userFavorites(ids, currentUser))
        .thenReturn(Collections.singleton("article-1"));
    when(userRelationshipQueryService.followingAuthors(
            currentUser.getId(), Arrays.asList("author-1", "author-2")))
        .thenReturn(Collections.singleton("author-1"));

    articleQueryService.findRecentArticlesWithCursor(null, null, null, page, currentUser);

    assertTrue(articles.get(0).isFavorited());
    assertTrue(articles.get(0).getProfileData().isFollowing());
    assertFalse(articles.get(1).isFavorited());
    assertFalse(articles.get(1).getProfileData().isFollowing());
  }

  @Test
  public void userFeedWithNoFollowedUsersReturnsEmptyPager() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    when(userRelationshipQueryService.followedUsers(currentUser.getId()))
        .thenReturn(Collections.emptyList());

    CursorPager<ArticleData> result = articleQueryService.findUserFeedWithCursor(currentUser, page);

    assertTrue(result.getData().isEmpty());
    assertFalse(result.hasNext());
    assertFalse(result.hasPrevious());
    verify(articleReadService, never()).findArticlesOfAuthorsWithCursor(anyList(), any());
  }

  @Test
  public void userFeedWithExtraArticleHasNextPage() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> followedUsers = Collections.singletonList("author-1");
    List<ArticleData> articles =
        new ArrayList<>(
            Arrays.asList(
                article("article-1", "author-1"),
                article("article-2", "author-1"),
                article("extra", "author-1")));
    when(userRelationshipQueryService.followedUsers(currentUser.getId())).thenReturn(followedUsers);
    when(articleReadService.findArticlesOfAuthorsWithCursor(followedUsers, page))
        .thenReturn(articles);
    stubListExtraInfo(currentUser, articles);

    CursorPager<ArticleData> result = articleQueryService.findUserFeedWithCursor(currentUser, page);

    assertEquals(2, result.getData().size());
    assertEquals("article-1", result.getData().get(0).getId());
    assertEquals("article-2", result.getData().get(1).getId());
    assertTrue(result.hasNext());
    assertFalse(result.hasPrevious());
  }

  @Test
  public void userFeedWithExactlyLimitArticlesHasNoNextPage() {
    CursorPageParameter<DateTime> page = page(2, Direction.NEXT);
    List<String> followedUsers = Collections.singletonList("author-1");
    List<ArticleData> articles =
        new ArrayList<>(
            Arrays.asList(article("article-1", "author-1"), article("article-2", "author-1")));
    when(userRelationshipQueryService.followedUsers(currentUser.getId())).thenReturn(followedUsers);
    when(articleReadService.findArticlesOfAuthorsWithCursor(followedUsers, page))
        .thenReturn(articles);
    stubListExtraInfo(currentUser, articles);

    CursorPager<ArticleData> result = articleQueryService.findUserFeedWithCursor(currentUser, page);

    assertEquals(2, result.getData().size());
    assertFalse(result.hasNext());
  }

  @Test
  public void previousUserFeedTrimsAndReversesArticles() {
    CursorPageParameter<DateTime> page = page(2, Direction.PREV);
    List<String> followedUsers = Collections.singletonList("author-1");
    List<ArticleData> articles =
        new ArrayList<>(
            Arrays.asList(
                article("article-1", "author-1"),
                article("article-2", "author-1"),
                article("extra", "author-1")));
    when(userRelationshipQueryService.followedUsers(currentUser.getId())).thenReturn(followedUsers);
    when(articleReadService.findArticlesOfAuthorsWithCursor(followedUsers, page))
        .thenReturn(articles);
    stubListExtraInfo(currentUser, articles);

    CursorPager<ArticleData> result = articleQueryService.findUserFeedWithCursor(currentUser, page);

    assertEquals("article-2", result.getData().get(0).getId());
    assertEquals("article-1", result.getData().get(1).getId());
    assertTrue(result.hasPrevious());
    assertFalse(result.hasNext());
  }

  private void stubListExtraInfo(User user, List<ArticleData> articles) {
    when(articleFavoritesReadService.articlesFavoriteCount(anyList()))
        .thenReturn(
            Arrays.asList(
                new ArticleFavoriteCount("article-1", 0),
                new ArticleFavoriteCount("article-2", 0)));
    when(articleFavoritesReadService.userFavorites(anyList(), eq(user)))
        .thenReturn(Collections.emptySet());
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(Collections.emptySet());
  }

  private CursorPageParameter<DateTime> page(int limit, Direction direction) {
    return new CursorPageParameter<>(null, limit, direction);
  }

  private ArticleData article(String id, String authorId) {
    return new ArticleData(
        id,
        id + "-slug",
        "title",
        "description",
        "body",
        false,
        0,
        new DateTime(),
        new DateTime(),
        Collections.singletonList("java"),
        new ProfileData(authorId, authorId, "bio", "image", false));
  }
}
