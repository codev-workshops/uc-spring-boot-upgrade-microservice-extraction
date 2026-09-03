package io.spring.infrastructure.extraction.favorite;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The {@code favoritedBy} filter has two implementations: the original SQL join and, when the
 * favorite route is extracted, an id list resolved through {@link FavoriteQueryPort}. Both must
 * page and order identically.
 */
@Import({
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleFavoriteRepository.class
})
public class FavoritedByIdListParityTest extends DbTestBase {
  @Autowired private ArticleReadService articleReadService;
  @Autowired private ArticleFavoritesReadService favoritesReadService;
  @Autowired private UserReadService userReadService;
  @Autowired private UserRelationshipQueryService userRelationshipQueryService;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  private User reader;
  private User author;
  private User otherAuthor;
  private ArticleQueryService joinPath;
  private ArticleQueryService idListPath;

  @BeforeEach
  public void setUp() {
    reader = new User("reader@test.com", "reader", "123", "", "");
    author = new User("author@test.com", "author", "123", "", "");
    otherAuthor = new User("other@test.com", "other", "123", "", "");
    userRepository.save(reader);
    userRepository.save(author);
    userRepository.save(otherAuthor);

    DateTime base = new DateTime(2024, 1, 1, 12, 0);
    for (int i = 0; i < 12; i++) {
      User owner = i % 3 == 0 ? otherAuthor : author;
      List<String> tags = i % 2 == 0 ? Arrays.asList("java", "spring") : Arrays.asList("java");
      Article article =
          new Article(
              "article " + i, "desc " + i, "body " + i, tags, owner.getId(), base.plusMinutes(i));
      articleRepository.save(article);
      if (i % 4 != 1) {
        articleFavoriteRepository.save(new ArticleFavorite(article.getId(), reader.getId()));
      }
    }

    joinPath =
        new ArticleQueryService(
            articleReadService,
            userRelationshipQueryService,
            favoritesReadService,
            userReadService);
    idListPath =
        new ArticleQueryService(
            articleReadService,
            userRelationshipQueryService,
            new IdListPort(favoritesReadService),
            userReadService);
  }

  @Test
  public void offset_pages_match_between_join_and_id_list() {
    for (String tag : Arrays.asList(null, "spring")) {
      for (String authorName : Arrays.asList(null, "author")) {
        for (int offset : Arrays.asList(0, 3, 8)) {
          Page page = new Page(offset, 4);
          ArticleDataList expected =
              joinPath.findRecentArticles(tag, authorName, "reader", page, reader);
          ArticleDataList actual =
              idListPath.findRecentArticles(tag, authorName, "reader", page, reader);
          String scenario = "tag=" + tag + " author=" + authorName + " offset=" + offset;
          Assertions.assertEquals(expected.getCount(), actual.getCount(), scenario);
          Assertions.assertEquals(
              slugs(expected.getArticleDatas()), slugs(actual.getArticleDatas()), scenario);
          Assertions.assertEquals(expected.getArticleDatas(), actual.getArticleDatas(), scenario);
        }
      }
    }
  }

  @Test
  public void cursor_pages_match_between_join_and_id_list_in_both_directions() {
    DateTime cursor = new DateTime(2024, 1, 1, 12, 6);
    List<CursorPageParameter<DateTime>> pages =
        Arrays.asList(
            new CursorPageParameter<>(null, 4, Direction.NEXT),
            new CursorPageParameter<>(cursor, 4, Direction.NEXT),
            new CursorPageParameter<>(cursor, 4, Direction.PREV),
            new CursorPageParameter<>(cursor, 50, Direction.PREV));
    for (String tag : Arrays.asList(null, "spring")) {
      for (CursorPageParameter<DateTime> page : pages) {
        CursorPager<ArticleData> expected =
            joinPath.findRecentArticlesWithCursor(tag, null, "reader", page, reader);
        CursorPager<ArticleData> actual =
            idListPath.findRecentArticlesWithCursor(tag, null, "reader", page, reader);
        String scenario = "tag=" + tag + " page=" + page;
        Assertions.assertEquals(slugs(expected.getData()), slugs(actual.getData()), scenario);
        Assertions.assertEquals(expected.getData(), actual.getData(), scenario);
        Assertions.assertEquals(expected.hasNext(), actual.hasNext(), scenario);
        Assertions.assertEquals(expected.hasPrevious(), actual.hasPrevious(), scenario);
      }
    }
  }

  @Test
  public void unknown_user_and_user_without_favorites_yield_empty_results() {
    Page page = new Page(0, 20);
    Assertions.assertEquals(
        joinPath.findRecentArticles(null, null, "nobody", page, null).getCount(),
        idListPath.findRecentArticles(null, null, "nobody", page, null).getCount());
    Assertions.assertEquals(
        0, idListPath.findRecentArticles(null, null, "nobody", page, null).getCount());
    Assertions.assertEquals(
        0,
        idListPath.findRecentArticles(null, null, "author", page, null).getArticleDatas().size());
    Assertions.assertTrue(
        idListPath
            .findRecentArticlesWithCursor(
                null, null, "author", new CursorPageParameter<>(null, 4, Direction.NEXT), null)
            .getData()
            .isEmpty());
  }

  @Test
  public void without_favorited_by_the_id_list_port_is_never_consulted() {
    ArticleDataList expected =
        joinPath.findRecentArticles("java", null, null, new Page(0, 5), reader);
    ArticleDataList actual =
        idListPath.findRecentArticles("java", null, null, new Page(0, 5), reader);
    Assertions.assertEquals(expected.getArticleDatas(), actual.getArticleDatas());
  }

  private static List<String> slugs(List<ArticleData> articles) {
    return articles.stream().map(ArticleData::getSlug).collect(Collectors.toList());
  }

  /** Same data as the MyBatis adapter, but claims the favoritedBy filter like the remote one. */
  private static class IdListPort implements FavoriteQueryPort {
    private final ArticleFavoritesReadService delegate;

    IdListPort(ArticleFavoritesReadService delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isUserFavorite(String userId, String articleId) {
      return delegate.isUserFavorite(userId, articleId);
    }

    @Override
    public int articleFavoriteCount(String articleId) {
      return delegate.articleFavoriteCount(articleId);
    }

    @Override
    public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids) {
      return ids.isEmpty() ? new ArrayList<>() : delegate.articlesFavoriteCount(ids);
    }

    @Override
    public Set<String> userFavorites(List<String> ids, User currentUser) {
      return ids.isEmpty() ? Collections.emptySet() : delegate.userFavorites(ids, currentUser);
    }

    @Override
    public List<String> articleIdsFavoritedBy(String userId) {
      return delegate.articleIdsFavoritedBy(userId);
    }

    @Override
    public boolean ownsFavoritedByFilter() {
      return true;
    }
  }
}
