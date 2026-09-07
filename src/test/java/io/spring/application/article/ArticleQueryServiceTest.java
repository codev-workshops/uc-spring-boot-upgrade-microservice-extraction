package io.spring.application.article;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  ArticleQueryService.class,
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleFavoriteRepository.class
})
public class ArticleQueryServiceTest extends DbTestBase {
  @Autowired private ArticleQueryService queryService;

  @Autowired private ArticleRepository articleRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  private User user;
  private Article article;

  @BeforeEach
  public void setUp() {
    user = new User("aisensiy@gmail.com", "aisensiy", "123", "", "");
    userRepository.save(user);
    article =
        new Article(
            "test", "desc", "body", Arrays.asList("java", "spring"), user.getId(), new DateTime());
    articleRepository.save(article);
  }

  @Test
  public void should_fetch_article_success() {
    Optional<ArticleData> optional = queryService.findById(article.getId(), user);
    Assertions.assertTrue(optional.isPresent());

    ArticleData fetched = optional.get();
    Assertions.assertEquals(fetched.getFavoritesCount(), 0);
    Assertions.assertFalse(fetched.isFavorited());
    Assertions.assertNotNull(fetched.getCreatedAt());
    Assertions.assertNotNull(fetched.getUpdatedAt());
    Assertions.assertTrue(fetched.getTagList().contains("java"));
  }

  @Test
  public void should_get_article_with_right_favorite_and_favorite_count() {
    User anotherUser = new User("other@test.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), anotherUser.getId()));

    Optional<ArticleData> optional = queryService.findById(article.getId(), anotherUser);
    Assertions.assertTrue(optional.isPresent());

    ArticleData articleData = optional.get();
    Assertions.assertEquals(articleData.getFavoritesCount(), 1);
    Assertions.assertTrue(articleData.isFavorited());
  }

  @Test
  public void should_get_default_article_list() {
    Article anotherArticle =
        new Article(
            "new article",
            "desc",
            "body",
            Arrays.asList("test"),
            user.getId(),
            new DateTime().minusHours(1));
    articleRepository.save(anotherArticle);

    ArticleDataList recentArticles =
        queryService.findRecentArticles(null, null, null, new Page(), user);
    Assertions.assertEquals(recentArticles.getCount(), 2);
    Assertions.assertEquals(recentArticles.getArticleDatas().size(), 2);
    Assertions.assertEquals(recentArticles.getArticleDatas().get(0).getId(), article.getId());

    ArticleDataList nodata =
        queryService.findRecentArticles(null, null, null, new Page(2, 10), user);
    Assertions.assertEquals(nodata.getCount(), 2);
    Assertions.assertEquals(nodata.getArticleDatas().size(), 0);
  }

  @Test
  public void should_get_default_article_list_by_cursor() {
    Article anotherArticle =
        new Article(
            "new article",
            "desc",
            "body",
            Arrays.asList("test"),
            user.getId(),
            new DateTime().minusHours(1));
    articleRepository.save(anotherArticle);

    CursorPager<ArticleData> recentArticles =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(null, 20, Direction.NEXT), user);
    Assertions.assertEquals(recentArticles.getData().size(), 2);
    Assertions.assertEquals(recentArticles.getData().get(0).getId(), article.getId());

    CursorPager<ArticleData> nodata =
        queryService.findRecentArticlesWithCursor(
            null,
            null,
            null,
            new CursorPageParameter<DateTime>(
                DateTimeCursor.parse(recentArticles.getEndCursor().toString()), 20, Direction.NEXT),
            user);
    Assertions.assertEquals(nodata.getData().size(), 0);
    Assertions.assertEquals(nodata.getStartCursor(), null);

    CursorPager<ArticleData> prevArticles =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(null, 20, Direction.PREV), user);
    Assertions.assertEquals(prevArticles.getData().size(), 2);
    Assertions.assertEquals(article.getId(), prevArticles.getData().get(0).getId());
    Assertions.assertEquals(anotherArticle.getId(), prevArticles.getData().get(1).getId());
    Assertions.assertFalse(prevArticles.hasPrevious());
  }

  @Test
  public void should_page_article_list_by_cursor_with_limit() {
    Article older =
        new Article(
            "older",
            "desc",
            "body",
            Arrays.asList("test"),
            user.getId(),
            new DateTime().minusHours(2));
    articleRepository.save(older);
    Article oldest =
        new Article(
            "oldest",
            "desc",
            "body",
            Arrays.asList("test"),
            user.getId(),
            new DateTime().minusHours(4));
    articleRepository.save(oldest);

    CursorPager<ArticleData> firstPage =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(null, 2, Direction.NEXT), user);
    Assertions.assertEquals(2, firstPage.getData().size());
    Assertions.assertEquals(article.getId(), firstPage.getData().get(0).getId());
    Assertions.assertEquals(older.getId(), firstPage.getData().get(1).getId());
    Assertions.assertTrue(firstPage.hasNext());
    Assertions.assertFalse(firstPage.hasPrevious());

    CursorPager<ArticleData> secondPage =
        queryService.findRecentArticlesWithCursor(
            null,
            null,
            null,
            new CursorPageParameter<>(
                DateTimeCursor.parse(firstPage.getEndCursor().toString()), 2, Direction.NEXT),
            user);
    Assertions.assertEquals(1, secondPage.getData().size());
    Assertions.assertEquals(oldest.getId(), secondPage.getData().get(0).getId());
    Assertions.assertFalse(secondPage.hasNext());

    DateTime oldestCursor = DateTimeCursor.parse(secondPage.getStartCursor().toString());
    CursorPager<ArticleData> prevPage =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(oldestCursor, 2, Direction.PREV), user);
    Assertions.assertEquals(2, prevPage.getData().size());
    Assertions.assertEquals(article.getId(), prevPage.getData().get(0).getId());
    Assertions.assertEquals(older.getId(), prevPage.getData().get(1).getId());
    Assertions.assertFalse(prevPage.hasPrevious());
    Assertions.assertFalse(prevPage.hasNext());

    CursorPager<ArticleData> prevPageWithExtra =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(oldestCursor, 1, Direction.PREV), user);
    Assertions.assertEquals(1, prevPageWithExtra.getData().size());
    Assertions.assertEquals(older.getId(), prevPageWithExtra.getData().get(0).getId());
    Assertions.assertTrue(prevPageWithExtra.hasPrevious());
    Assertions.assertFalse(prevPageWithExtra.hasNext());
  }

  @Test
  public void should_fill_extra_info_in_cursor_article_list() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    userRepository.saveRelation(new FollowRelation(anotherUser.getId(), user.getId()));
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), anotherUser.getId()));

    CursorPager<ArticleData> anonymous =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(null, 20, Direction.NEXT), null);
    ArticleData anonymousData = anonymous.getData().get(0);
    Assertions.assertEquals(1, anonymousData.getFavoritesCount());
    Assertions.assertFalse(anonymousData.isFavorited());
    Assertions.assertFalse(anonymousData.getProfileData().isFollowing());

    CursorPager<ArticleData> withUser =
        queryService.findRecentArticlesWithCursor(
            null, null, null, new CursorPageParameter<>(null, 20, Direction.NEXT), anotherUser);
    ArticleData articleData = withUser.getData().get(0);
    Assertions.assertEquals(1, articleData.getFavoritesCount());
    Assertions.assertTrue(articleData.isFavorited());
    Assertions.assertTrue(articleData.getProfileData().isFollowing());
  }

  @Test
  public void should_fetch_article_by_slug() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    userRepository.saveRelation(new FollowRelation(anotherUser.getId(), user.getId()));
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), anotherUser.getId()));

    Optional<ArticleData> anonymous = queryService.findBySlug(article.getSlug(), null);
    Assertions.assertTrue(anonymous.isPresent());
    Assertions.assertEquals(article.getId(), anonymous.get().getId());
    Assertions.assertFalse(anonymous.get().isFavorited());
    Assertions.assertFalse(anonymous.get().getProfileData().isFollowing());

    Optional<ArticleData> optional = queryService.findBySlug(article.getSlug(), anotherUser);
    Assertions.assertTrue(optional.isPresent());
    ArticleData fetched = optional.get();
    Assertions.assertEquals(article.getId(), fetched.getId());
    Assertions.assertEquals(1, fetched.getFavoritesCount());
    Assertions.assertTrue(fetched.isFavorited());
    Assertions.assertTrue(fetched.getProfileData().isFollowing());

    Assertions.assertFalse(queryService.findBySlug("not-exist", user).isPresent());
  }

  @Test
  public void should_return_empty_when_article_not_found_by_id() {
    Assertions.assertFalse(queryService.findById("not-exist", user).isPresent());
    Assertions.assertFalse(queryService.findById("not-exist", null).isPresent());
  }

  @Test
  public void should_fetch_article_by_id_with_following_flag() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    userRepository.saveRelation(new FollowRelation(anotherUser.getId(), user.getId()));

    Assertions.assertTrue(
        queryService.findById(article.getId(), anotherUser).get().getProfileData().isFollowing());
    Assertions.assertFalse(
        queryService.findById(article.getId(), user).get().getProfileData().isFollowing());

    Optional<ArticleData> anonymous = queryService.findById(article.getId(), null);
    Assertions.assertTrue(anonymous.isPresent());
    Assertions.assertFalse(anonymous.get().isFavorited());
    Assertions.assertFalse(anonymous.get().getProfileData().isFollowing());
  }

  @Test
  public void should_get_user_feed_by_cursor() {
    User author = new User("author@email.com", "author", "123", "", "");
    userRepository.save(author);
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    userRepository.saveRelation(new FollowRelation(anotherUser.getId(), author.getId()));

    Article newest =
        new Article(
            "newest", "desc", "body", Arrays.asList("test"), author.getId(), new DateTime());
    articleRepository.save(newest);
    Article older =
        new Article(
            "older",
            "desc",
            "body",
            Arrays.asList("test"),
            author.getId(),
            new DateTime().minusHours(2));
    articleRepository.save(older);
    Article oldest =
        new Article(
            "oldest",
            "desc",
            "body",
            Arrays.asList("test"),
            author.getId(),
            new DateTime().minusHours(4));
    articleRepository.save(oldest);
    articleFavoriteRepository.save(new ArticleFavorite(newest.getId(), anotherUser.getId()));

    CursorPager<ArticleData> noFollowing =
        queryService.findUserFeedWithCursor(
            user, new CursorPageParameter<>(null, 20, Direction.NEXT));
    Assertions.assertTrue(noFollowing.getData().isEmpty());
    Assertions.assertFalse(noFollowing.hasNext());

    CursorPager<ArticleData> firstPage =
        queryService.findUserFeedWithCursor(
            anotherUser, new CursorPageParameter<>(null, 2, Direction.NEXT));
    Assertions.assertEquals(2, firstPage.getData().size());
    Assertions.assertEquals(newest.getId(), firstPage.getData().get(0).getId());
    Assertions.assertEquals(older.getId(), firstPage.getData().get(1).getId());
    Assertions.assertTrue(firstPage.hasNext());
    Assertions.assertFalse(firstPage.hasPrevious());
    ArticleData first = firstPage.getData().get(0);
    Assertions.assertEquals(1, first.getFavoritesCount());
    Assertions.assertTrue(first.isFavorited());
    Assertions.assertTrue(first.getProfileData().isFollowing());

    CursorPager<ArticleData> secondPage =
        queryService.findUserFeedWithCursor(
            anotherUser,
            new CursorPageParameter<>(
                DateTimeCursor.parse(firstPage.getEndCursor().toString()), 2, Direction.NEXT));
    Assertions.assertEquals(1, secondPage.getData().size());
    Assertions.assertEquals(oldest.getId(), secondPage.getData().get(0).getId());
    Assertions.assertFalse(secondPage.hasNext());

    DateTime oldestCursor = DateTimeCursor.parse(secondPage.getStartCursor().toString());
    CursorPager<ArticleData> prevPage =
        queryService.findUserFeedWithCursor(
            anotherUser, new CursorPageParameter<>(oldestCursor, 2, Direction.PREV));
    Assertions.assertEquals(2, prevPage.getData().size());
    Assertions.assertEquals(newest.getId(), prevPage.getData().get(0).getId());
    Assertions.assertEquals(older.getId(), prevPage.getData().get(1).getId());
    Assertions.assertFalse(prevPage.hasPrevious());

    CursorPager<ArticleData> prevPageWithExtra =
        queryService.findUserFeedWithCursor(
            anotherUser, new CursorPageParameter<>(oldestCursor, 1, Direction.PREV));
    Assertions.assertEquals(1, prevPageWithExtra.getData().size());
    Assertions.assertEquals(older.getId(), prevPageWithExtra.getData().get(0).getId());
    Assertions.assertTrue(prevPageWithExtra.hasPrevious());
  }

  @Test
  public void should_query_article_by_author() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);

    Article anotherArticle =
        new Article("new article", "desc", "body", Arrays.asList("test"), anotherUser.getId());
    articleRepository.save(anotherArticle);

    ArticleDataList recentArticles =
        queryService.findRecentArticles(null, user.getUsername(), null, new Page(), user);
    Assertions.assertEquals(recentArticles.getArticleDatas().size(), 1);
    Assertions.assertEquals(recentArticles.getCount(), 1);
  }

  @Test
  public void should_query_article_by_favorite() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);

    Article anotherArticle =
        new Article("new article", "desc", "body", Arrays.asList("test"), anotherUser.getId());
    articleRepository.save(anotherArticle);

    ArticleFavorite articleFavorite = new ArticleFavorite(article.getId(), anotherUser.getId());
    articleFavoriteRepository.save(articleFavorite);

    ArticleDataList recentArticles =
        queryService.findRecentArticles(
            null, null, anotherUser.getUsername(), new Page(), anotherUser);
    Assertions.assertEquals(recentArticles.getArticleDatas().size(), 1);
    Assertions.assertEquals(recentArticles.getCount(), 1);
    ArticleData articleData = recentArticles.getArticleDatas().get(0);
    Assertions.assertEquals(articleData.getId(), article.getId());
    Assertions.assertEquals(articleData.getFavoritesCount(), 1);
    Assertions.assertTrue(articleData.isFavorited());
  }

  @Test
  public void should_query_article_by_tag() {
    Article anotherArticle =
        new Article("new article", "desc", "body", Arrays.asList("test"), user.getId());
    articleRepository.save(anotherArticle);

    ArticleDataList recentArticles =
        queryService.findRecentArticles("spring", null, null, new Page(), user);
    Assertions.assertEquals(recentArticles.getArticleDatas().size(), 1);
    Assertions.assertEquals(recentArticles.getCount(), 1);
    Assertions.assertEquals(recentArticles.getArticleDatas().get(0).getId(), article.getId());

    ArticleDataList notag = queryService.findRecentArticles("notag", null, null, new Page(), user);
    Assertions.assertEquals(notag.getCount(), 0);
  }

  @Test
  public void should_show_following_if_user_followed_author() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);

    FollowRelation followRelation = new FollowRelation(anotherUser.getId(), user.getId());
    userRepository.saveRelation(followRelation);

    ArticleDataList recentArticles =
        queryService.findRecentArticles(null, null, null, new Page(), anotherUser);
    Assertions.assertEquals(recentArticles.getCount(), 1);
    ArticleData articleData = recentArticles.getArticleDatas().get(0);
    Assertions.assertTrue(articleData.getProfileData().isFollowing());
  }

  @Test
  public void should_get_user_feed() {
    User anotherUser = new User("other@email.com", "other", "123", "", "");
    userRepository.save(anotherUser);

    FollowRelation followRelation = new FollowRelation(anotherUser.getId(), user.getId());
    userRepository.saveRelation(followRelation);

    ArticleDataList userFeed = queryService.findUserFeed(user, new Page());
    Assertions.assertEquals(userFeed.getCount(), 0);

    ArticleDataList anotherUserFeed = queryService.findUserFeed(anotherUser, new Page());
    Assertions.assertEquals(anotherUserFeed.getCount(), 1);
    ArticleData articleData = anotherUserFeed.getArticleDatas().get(0);
    Assertions.assertTrue(articleData.getProfileData().isFollowing());
  }
}
