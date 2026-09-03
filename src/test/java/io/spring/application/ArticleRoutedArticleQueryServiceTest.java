package io.spring.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.CursorPager.Direction;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleQueryPort;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ArticleRow;
import io.spring.application.data.UserData;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.application.tag.TagQueryPort;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleQueryService} as a composer once the Article port owns reads: rows (+tagList) from
 * the port, author profile / following / favorites composed locally, username filters resolved
 * before the port is called, Tag port never consulted.
 */
public class ArticleRoutedArticleQueryServiceTest {
  private final ArticleReadService articleReadService = mock(ArticleReadService.class);
  private final UserRelationshipQueryService relationships =
      mock(UserRelationshipQueryService.class);
  private final FavoriteQueryPort favorites = mock(FavoriteQueryPort.class);
  private final UserReadService users = mock(UserReadService.class);
  private final TagQueryPort tags = mock(TagQueryPort.class);
  private final ArticleQueryPort articles = mock(ArticleQueryPort.class);
  private final ArticleQueryService service =
      new ArticleQueryService(articleReadService, relationships, favorites, users, tags, articles);

  private final User reader = new User("r@t.com", "reader", "123", "", "");
  private final UserData author = new UserData("u1", "a@t.com", "author", "bio", "img");
  private final ArticleRow javaRow =
      row("a1", "java-article", "u1", Arrays.asList("java", "spring"));
  private final ArticleRow bareRow = row("a2", "bare-article", "u1", Collections.emptyList());
  private final Page page = new Page(0, 20);

  @BeforeEach
  public void setUp() {
    when(articles.ownsArticleReads()).thenReturn(true);
    when(tags.ownsTagReads()).thenReturn(true);
    when(users.findByIds(anyList())).thenReturn(Collections.singletonList(author));
    when(favorites.articlesFavoriteCount(anyList()))
        .thenAnswer(
            call ->
                ((List<String>) call.getArgument(0))
                    .stream()
                        .map(id -> new ArticleFavoriteCount(id, "a1".equals(id) ? 1 : 0))
                        .collect(java.util.stream.Collectors.toList()));
    when(favorites.userFavorites(anyList(), any()))
        .thenReturn(new HashSet<>(Collections.singletonList("a1")));
    when(relationships.followingAuthors(anyString(), anyList()))
        .thenReturn(new HashSet<>(Collections.singletonList("u1")));
  }

  @Test
  public void by_slug_composes_the_row_with_local_profile_following_and_favorites() {
    when(articles.findBySlug("java-article")).thenReturn(Optional.of(javaRow));
    when(favorites.isUserFavorite(reader.getId(), "a1")).thenReturn(true);
    when(favorites.articleFavoriteCount("a1")).thenReturn(3);
    when(relationships.isUserFollowing(reader.getId(), "u1")).thenReturn(true);

    ArticleData data = service.findBySlug("java-article", reader).get();

    Assertions.assertEquals("a1", data.getId());
    Assertions.assertEquals(Arrays.asList("java", "spring"), data.getTagList());
    Assertions.assertEquals("author", data.getProfileData().getUsername());
    Assertions.assertEquals("bio", data.getProfileData().getBio());
    Assertions.assertTrue(data.getProfileData().isFollowing());
    Assertions.assertTrue(data.isFavorited());
    Assertions.assertEquals(3, data.getFavoritesCount());
    Assertions.assertEquals(javaRow.getCreatedAt(), data.getCreatedAt());
    verifyNoInteractions(articleReadService, tags);
  }

  @Test
  public void anonymous_by_id_leaves_favorited_and_following_false_and_missing_is_empty() {
    when(articles.findById("a2")).thenReturn(Optional.of(bareRow));
    when(articles.findById("nope")).thenReturn(Optional.empty());

    ArticleData data = service.findById("a2", null).get();
    Assertions.assertFalse(data.isFavorited());
    Assertions.assertFalse(data.getProfileData().isFollowing());
    Assertions.assertTrue(data.getTagList().isEmpty());
    Assertions.assertFalse(service.findById("nope", null).isPresent());
    verifyNoInteractions(favorites, relationships, tags);
  }

  @Test
  public void list_resolves_author_and_favorited_usernames_before_calling_the_port() {
    when(users.findByUsername("author")).thenReturn(author);
    when(users.findByUsername("reader")).thenReturn(new UserData("r1", "", "reader", "", ""));
    when(favorites.articleIdsFavoritedBy("r1")).thenReturn(Arrays.asList("a1", "a2"));
    when(articles.queryArticleIds("java", "u1", Arrays.asList("a1", "a2"), page))
        .thenReturn(new ArticleIdPage(Collections.singletonList("a1"), 7));
    when(articles.findArticles(Collections.singletonList("a1")))
        .thenReturn(Collections.singletonList(javaRow));

    ArticleDataList list = service.findRecentArticles("java", "author", "reader", page, reader);

    Assertions.assertEquals(7, list.getCount());
    Assertions.assertEquals(1, list.getArticleDatas().size());
    ArticleData data = list.getArticleDatas().get(0);
    Assertions.assertEquals(Arrays.asList("java", "spring"), data.getTagList());
    Assertions.assertTrue(data.isFavorited());
    Assertions.assertEquals(1, data.getFavoritesCount());
    Assertions.assertTrue(data.getProfileData().isFollowing());
    verify(tags, never()).articleIdsByTag(anyString());
    verify(tags, never()).tagsByArticleIds(anyList());
    verifyNoInteractions(articleReadService);
  }

  @Test
  public void unknown_author_or_favoriting_user_yields_an_empty_page_without_a_remote_call() {
    when(users.findByUsername("ghost")).thenReturn(null);
    when(users.findByUsername("nofav")).thenReturn(new UserData("r2", "", "nofav", "", ""));
    when(favorites.articleIdsFavoritedBy("r2")).thenReturn(Collections.emptyList());

    ArticleDataList byAuthor = service.findRecentArticles(null, "ghost", null, page, null);
    ArticleDataList byFavorited = service.findRecentArticles(null, null, "nofav", page, null);
    CursorPager<ArticleData> cursor =
        service.findRecentArticlesWithCursor(
            null, "ghost", null, new CursorPageParameter<>(null, 20, Direction.NEXT), null);

    Assertions.assertEquals(0, byAuthor.getCount());
    Assertions.assertTrue(byAuthor.getArticleDatas().isEmpty());
    Assertions.assertEquals(0, byFavorited.getCount());
    Assertions.assertTrue(cursor.getData().isEmpty());
    Assertions.assertFalse(cursor.hasNext());
    verify(articles, never()).queryArticleIds(any(), any(), any(), any());
    verify(articles, never()).queryArticleIdsWithCursor(any(), any(), any(), any());
  }

  @Test
  public void empty_id_page_keeps_the_total_count_without_fetching_rows() {
    when(articles.queryArticleIds(null, null, null, new Page(40, 20)))
        .thenReturn(new ArticleIdPage(Collections.emptyList(), 2));

    ArticleDataList list = service.findRecentArticles(null, null, null, new Page(40, 20), null);

    Assertions.assertEquals(2, list.getCount());
    Assertions.assertTrue(list.getArticleDatas().isEmpty());
    verify(articles, never()).findArticles(anyList());
  }

  @Test
  public void cursor_list_probes_limit_plus_one_and_reverses_previous_pages() {
    CursorPageParameter<DateTime> next = new CursorPageParameter<>(null, 1, Direction.NEXT);
    when(articles.queryArticleIdsWithCursor(null, null, null, next))
        .thenReturn(Arrays.asList("a1", "a2"));
    when(articles.findArticles(Collections.singletonList("a1")))
        .thenReturn(Collections.singletonList(javaRow));

    CursorPager<ArticleData> page =
        service.findRecentArticlesWithCursor(null, null, null, next, null);
    Assertions.assertTrue(page.hasNext());
    Assertions.assertEquals(1, page.getData().size());
    Assertions.assertEquals("a1", page.getData().get(0).getId());

    CursorPageParameter<DateTime> prev =
        new CursorPageParameter<>(new DateTime(0), 5, Direction.PREV);
    when(articles.queryArticleIdsWithCursor(null, null, null, prev))
        .thenReturn(Arrays.asList("a2", "a1"));
    when(articles.findArticles(Arrays.asList("a1", "a2")))
        .thenReturn(Arrays.asList(javaRow, bareRow));
    CursorPager<ArticleData> previous =
        service.findRecentArticlesWithCursor(null, null, null, prev, null);
    Assertions.assertFalse(previous.hasNext());
    Assertions.assertFalse(previous.hasPrevious());
    Assertions.assertEquals(2, previous.getData().size());
  }

  @Test
  public void feed_routes_locally_resolved_followed_ids_through_the_port() {
    when(relationships.followedUsers(reader.getId())).thenReturn(Collections.singletonList("u1"));
    when(articles.findArticlesOfAuthors(Collections.singletonList("u1"), page))
        .thenReturn(new ArticleRowPage(Arrays.asList(javaRow, bareRow), 9));

    ArticleDataList feed = service.findUserFeed(reader, page);

    Assertions.assertEquals(9, feed.getCount());
    Assertions.assertEquals(2, feed.getArticleDatas().size());
    Assertions.assertEquals(1, feed.getArticleDatas().get(0).getFavoritesCount());
    Assertions.assertEquals(0, feed.getArticleDatas().get(1).getFavoritesCount());
    verifyNoInteractions(articleReadService, tags);
  }

  @Test
  public void feed_without_followed_users_never_calls_the_port() {
    when(relationships.followedUsers(reader.getId())).thenReturn(Collections.emptyList());
    Assertions.assertEquals(0, service.findUserFeed(reader, page).getCount());
    Assertions.assertTrue(
        service
            .findUserFeedWithCursor(reader, new CursorPageParameter<>(null, 20, Direction.NEXT))
            .getData()
            .isEmpty());
    verifyNoInteractions(articles);
  }

  @Test
  public void feed_cursor_keeps_limit_plus_one_and_reverse_semantics() {
    CursorPageParameter<DateTime> prev =
        new CursorPageParameter<>(new DateTime(0), 1, Direction.PREV);
    when(relationships.followedUsers(reader.getId())).thenReturn(Collections.singletonList("u1"));
    when(articles.findArticlesOfAuthorsWithCursor(Collections.singletonList("u1"), prev))
        .thenReturn(Arrays.asList(bareRow, javaRow));

    CursorPager<ArticleData> feed = service.findUserFeedWithCursor(reader, prev);

    Assertions.assertTrue(feed.hasPrevious());
    Assertions.assertFalse(feed.hasNext());
    Assertions.assertEquals(1, feed.getData().size());
    Assertions.assertEquals("a2", feed.getData().get(0).getId());
  }

  @Test
  public void when_the_port_does_not_own_reads_the_legacy_sql_path_is_used() {
    when(articles.ownsArticleReads()).thenReturn(false);
    when(articleReadService.findBySlug("java-article")).thenReturn(null);

    Assertions.assertFalse(service.findBySlug("java-article", null).isPresent());
    verify(articleReadService).findBySlug("java-article");
    verify(articles, never()).findBySlug(anyString());
  }

  private static ArticleRow row(String id, String slug, String userId, List<String> tagList) {
    DateTime at = new DateTime(2024, 1, 3, 0, 0);
    return new ArticleRow(id, slug, slug, "d", "b", userId, at, at, tagList);
  }
}
