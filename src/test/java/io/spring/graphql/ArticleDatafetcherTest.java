package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import io.spring.TestHelper;
import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.ArticleData;
import io.spring.application.data.CommentData;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.graphql.types.Article;
import io.spring.graphql.types.ArticlesConnection;
import io.spring.graphql.types.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ArticleDatafetcherTest extends GraphQLTestBase {
  private ArticleQueryService articleQueryService;
  private UserRepository userRepository;
  private ArticleDatafetcher articleDatafetcher;
  private User user;
  private ArticleData articleData;
  private DgsDataFetchingEnvironment dfe;

  @BeforeEach
  public void setUp() {
    articleQueryService = mock(ArticleQueryService.class);
    userRepository = mock(UserRepository.class);
    articleDatafetcher = new ArticleDatafetcher(articleQueryService, userRepository);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    articleData = TestHelper.articleDataFixture("1", user);
    dfe = mock(DgsDataFetchingEnvironment.class);
  }

  private CursorPager<ArticleData> pager(Direction direction, boolean hasExtra) {
    return new CursorPager<>(new ArrayList<>(Arrays.asList(articleData)), direction, hasExtra);
  }

  private void assertSingleArticle(DataFetcherResult<ArticlesConnection> result) {
    ArticlesConnection connection = result.getData();
    Assertions.assertEquals(1, connection.getEdges().size());
    Assertions.assertEquals(
        articleData.getCursor().toString(), connection.getEdges().get(0).getCursor());
    Article node = connection.getEdges().get(0).getNode();
    Assertions.assertEquals(articleData.getSlug(), node.getSlug());
    Assertions.assertEquals(articleData.getTitle(), node.getTitle());
    Assertions.assertEquals(articleData.getTagList(), node.getTagList());
    Assertions.assertEquals(
        articleData, ((Map<?, ?>) result.getLocalContext()).get(articleData.getSlug()));
  }

  private void assertForwardThenBackward(List<CursorPageParameter> params) {
    Assertions.assertEquals(2, params.size());
    CursorPageParameter forward = params.get(0);
    Assertions.assertEquals(Direction.NEXT, forward.getDirection());
    Assertions.assertEquals(3, forward.getLimit());
    Assertions.assertEquals(1000L, ((DateTime) forward.getCursor()).getMillis());
    CursorPageParameter backward = params.get(1);
    Assertions.assertEquals(Direction.PREV, backward.getDirection());
    Assertions.assertEquals(4, backward.getLimit());
    Assertions.assertEquals(2000L, ((DateTime) backward.getCursor()).getMillis());
  }

  private void assertForwardThenBackwardWithoutCursor(List<CursorPageParameter> params) {
    Assertions.assertEquals(2, params.size());
    CursorPageParameter forward = params.get(0);
    Assertions.assertEquals(Direction.NEXT, forward.getDirection());
    Assertions.assertEquals(3, forward.getLimit());
    Assertions.assertNull(forward.getCursor());
    CursorPageParameter backward = params.get(1);
    Assertions.assertEquals(Direction.PREV, backward.getDirection());
    Assertions.assertEquals(4, backward.getLimit());
    Assertions.assertNull(backward.getCursor());
  }

  @Test
  public void should_reject_feed_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> articleDatafetcher.getFeed(null, null, null, null, dfe));
  }

  @Test
  public void should_get_next_page_of_feed_for_current_user() {
    login(user);
    when(articleQueryService.findUserFeedWithCursor(eq(user), any()))
        .thenReturn(pager(Direction.NEXT, true));

    DataFetcherResult<ArticlesConnection> result =
        articleDatafetcher.getFeed(10, "1000", null, null, dfe);

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService).findUserFeedWithCursor(eq(user), captor.capture());
    Assertions.assertEquals(Direction.NEXT, captor.getValue().getDirection());
    Assertions.assertEquals(10, captor.getValue().getLimit());
    Assertions.assertEquals(1000L, ((DateTime) captor.getValue().getCursor()).getMillis());
    assertSingleArticle(result);
    Assertions.assertTrue(result.getData().getPageInfo().isHasNextPage());
    Assertions.assertFalse(result.getData().getPageInfo().isHasPreviousPage());
  }

  @Test
  public void should_get_previous_page_of_feed() {
    login(user);
    when(articleQueryService.findUserFeedWithCursor(eq(user), any()))
        .thenReturn(pager(Direction.PREV, true));

    DataFetcherResult<ArticlesConnection> result =
        articleDatafetcher.getFeed(null, null, 5, "2000", dfe);

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService).findUserFeedWithCursor(eq(user), captor.capture());
    Assertions.assertEquals(Direction.PREV, captor.getValue().getDirection());
    Assertions.assertEquals(5, captor.getValue().getLimit());
    Assertions.assertEquals(2000L, ((DateTime) captor.getValue().getCursor()).getMillis());
    assertSingleArticle(result);
    Assertions.assertTrue(result.getData().getPageInfo().isHasPreviousPage());
  }

  @Test
  public void should_get_feed_of_profile_user() {
    User target = new User("target@test.com", "target", "123", "", "");
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("target").build());
    when(userRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
    when(articleQueryService.findUserFeedWithCursor(eq(target), any()))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userFeed(3, "1000", null, null, dfe));
    assertSingleArticle(articleDatafetcher.userFeed(null, null, 4, "2000", dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2)).findUserFeedWithCursor(eq(target), captor.capture());
    assertForwardThenBackward(captor.getAllValues());
  }

  @Test
  public void should_throw_not_found_for_feed_of_unknown_profile() {
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("missing").build());
    when(userRepository.findByUsername(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class,
        () -> articleDatafetcher.userFeed(1, null, null, null, dfe));
  }

  @Test
  public void should_reject_profile_feed_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> articleDatafetcher.userFeed(null, null, null, null, dfe));
  }

  @Test
  public void should_get_favorites_of_profile_user() {
    anonymous();
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("johnjacob").build());
    when(articleQueryService.findRecentArticlesWithCursor(
            isNull(), isNull(), eq("johnjacob"), any(), isNull()))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userFavorites(3, "1000", null, null, dfe));
    assertSingleArticle(articleDatafetcher.userFavorites(null, null, 4, "2000", dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2))
        .findRecentArticlesWithCursor(
            isNull(), isNull(), eq("johnjacob"), captor.capture(), isNull());
    assertForwardThenBackward(captor.getAllValues());
  }

  @Test
  public void should_reject_favorites_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> articleDatafetcher.userFavorites(null, null, null, null, dfe));
  }

  @Test
  public void should_get_articles_of_profile_user() {
    login(user);
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("johnjacob").build());
    when(articleQueryService.findRecentArticlesWithCursor(
            isNull(), eq("johnjacob"), isNull(), any(), eq(user)))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userArticles(3, "1000", null, null, dfe));
    assertSingleArticle(articleDatafetcher.userArticles(null, null, 4, "2000", dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2))
        .findRecentArticlesWithCursor(
            isNull(), eq("johnjacob"), isNull(), captor.capture(), eq(user));
    assertForwardThenBackward(captor.getAllValues());
  }

  @Test
  public void should_get_feed_of_profile_user_without_cursor() {
    User target = new User("target@test.com", "target", "123", "", "");
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("target").build());
    when(userRepository.findByUsername(eq("target"))).thenReturn(Optional.of(target));
    when(articleQueryService.findUserFeedWithCursor(eq(target), any()))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userFeed(3, null, null, null, dfe));
    assertSingleArticle(articleDatafetcher.userFeed(null, null, 4, null, dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2)).findUserFeedWithCursor(eq(target), captor.capture());
    assertForwardThenBackwardWithoutCursor(captor.getAllValues());
  }

  @Test
  public void should_get_favorites_of_profile_user_without_cursor() {
    anonymous();
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("johnjacob").build());
    when(articleQueryService.findRecentArticlesWithCursor(
            isNull(), isNull(), eq("johnjacob"), any(), isNull()))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userFavorites(3, null, null, null, dfe));
    assertSingleArticle(articleDatafetcher.userFavorites(null, null, 4, null, dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2))
        .findRecentArticlesWithCursor(
            isNull(), isNull(), eq("johnjacob"), captor.capture(), isNull());
    assertForwardThenBackwardWithoutCursor(captor.getAllValues());
  }

  @Test
  public void should_get_articles_of_profile_user_without_cursor() {
    login(user);
    when(dfe.getSource()).thenReturn(Profile.newBuilder().username("johnjacob").build());
    when(articleQueryService.findRecentArticlesWithCursor(
            isNull(), eq("johnjacob"), isNull(), any(), eq(user)))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(articleDatafetcher.userArticles(3, null, null, null, dfe));
    assertSingleArticle(articleDatafetcher.userArticles(null, null, 4, null, dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2))
        .findRecentArticlesWithCursor(
            isNull(), eq("johnjacob"), isNull(), captor.capture(), eq(user));
    assertForwardThenBackwardWithoutCursor(captor.getAllValues());
  }

  @Test
  public void should_reject_profile_articles_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> articleDatafetcher.userArticles(null, null, null, null, dfe));
  }

  @Test
  public void should_get_articles_with_filters() {
    anonymous();
    when(articleQueryService.findRecentArticlesWithCursor(
            eq("java"), eq("author"), eq("fan"), any(), isNull()))
        .thenReturn(pager(Direction.NEXT, false));

    assertSingleArticle(
        articleDatafetcher.getArticles(3, "1000", null, null, "author", "fan", "java", dfe));
    assertSingleArticle(
        articleDatafetcher.getArticles(null, null, 4, "2000", "author", "fan", "java", dfe));

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService, times(2))
        .findRecentArticlesWithCursor(
            eq("java"), eq("author"), eq("fan"), captor.capture(), isNull());
    assertForwardThenBackward(captor.getAllValues());
  }

  @Test
  public void should_reject_articles_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> articleDatafetcher.getArticles(null, null, null, null, null, null, null, dfe));
  }

  @Test
  public void should_return_empty_connection_when_no_articles() {
    anonymous();
    when(articleQueryService.findRecentArticlesWithCursor(any(), any(), any(), any(), any()))
        .thenReturn(new CursorPager<>(new ArrayList<>(), Direction.NEXT, false));

    DataFetcherResult<ArticlesConnection> result =
        articleDatafetcher.getArticles(1, null, null, null, null, null, null, dfe);

    Assertions.assertTrue(result.getData().getEdges().isEmpty());
    Assertions.assertNull(result.getData().getPageInfo().getStartCursor());
    Assertions.assertNull(result.getData().getPageInfo().getEndCursor());
  }

  @Test
  public void should_get_article_from_payload_local_context() {
    login(user);
    io.spring.core.article.Article article =
        new io.spring.core.article.Article(
            "title", "desc", "body", Arrays.asList("java"), user.getId());
    DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
    when(env.getLocalContext()).thenReturn(article);
    when(articleQueryService.findById(eq(article.getId()), eq(user)))
        .thenReturn(Optional.of(articleData));

    DataFetcherResult<Article> result = articleDatafetcher.getArticle(env);

    Assertions.assertEquals(articleData.getSlug(), result.getData().getSlug());
    Assertions.assertEquals(articleData.getBody(), result.getData().getBody());
    Assertions.assertEquals(
        articleData, ((Map<?, ?>) result.getLocalContext()).get(articleData.getSlug()));
  }

  @Test
  public void should_throw_not_found_when_payload_article_missing() {
    anonymous();
    io.spring.core.article.Article article =
        new io.spring.core.article.Article(
            "title", "desc", "body", Arrays.asList("java"), user.getId());
    DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
    when(env.getLocalContext()).thenReturn(article);
    when(articleQueryService.findById(eq(article.getId()), isNull())).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> articleDatafetcher.getArticle(env));
  }

  @Test
  public void should_get_article_of_comment() {
    anonymous();
    DateTime now = new DateTime();
    CommentData commentData =
        new CommentData("c1", "body", articleData.getId(), now, now, articleData.getProfileData());
    DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
    when(env.getLocalContext()).thenReturn(commentData);
    when(articleQueryService.findById(eq(articleData.getId()), isNull()))
        .thenReturn(Optional.of(articleData));

    DataFetcherResult<Article> result = articleDatafetcher.getCommentArticle(env);

    Assertions.assertEquals(articleData.getSlug(), result.getData().getSlug());
  }

  @Test
  public void should_throw_not_found_when_comment_article_missing() {
    anonymous();
    DateTime now = new DateTime();
    CommentData commentData =
        new CommentData("c1", "body", "missing", now, now, articleData.getProfileData());
    DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
    when(env.getLocalContext()).thenReturn(commentData);
    when(articleQueryService.findById(eq("missing"), isNull())).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> articleDatafetcher.getCommentArticle(env));
  }

  @Test
  public void should_find_article_by_slug() {
    login(user);
    when(articleQueryService.findBySlug(eq(articleData.getSlug()), eq(user)))
        .thenReturn(Optional.of(articleData));

    DataFetcherResult<Article> result = articleDatafetcher.findArticleBySlug(articleData.getSlug());

    Assertions.assertEquals(articleData.getTitle(), result.getData().getTitle());
    Assertions.assertEquals(articleData.getFavoritesCount(), result.getData().getFavoritesCount());
  }

  @Test
  public void should_throw_not_found_for_unknown_slug() {
    anonymous();
    when(articleQueryService.findBySlug(eq("missing"), isNull())).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> articleDatafetcher.findArticleBySlug("missing"));
  }
}
