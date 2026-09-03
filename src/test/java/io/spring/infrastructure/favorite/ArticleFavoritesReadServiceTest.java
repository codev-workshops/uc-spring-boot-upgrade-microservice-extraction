package io.spring.infrastructure.favorite;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Batch behaviour of the favorite read service. The extracted favorite service must keep these
 * semantics because ArticleQueryService feeds it article-id batches of unbounded size.
 */
@Import({
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleFavoriteRepository.class
})
public class ArticleFavoritesReadServiceTest extends DbTestBase {
  @Autowired private ArticleFavoritesReadService articleFavoritesReadService;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  private User user;

  @BeforeEach
  public void setUp() {
    user = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(user);
  }

  @Test
  public void should_return_zero_count_row_for_article_without_favorites() {
    Article article = saveArticle("lonely article");

    List<ArticleFavoriteCount> counts =
        articleFavoritesReadService.articlesFavoriteCount(
            Collections.singletonList(article.getId()));

    Assertions.assertEquals(1, counts.size());
    Assertions.assertEquals(article.getId(), counts.get(0).getId());
    Assertions.assertEquals(0, counts.get(0).getCount());
  }

  @Test
  public void should_skip_unknown_article_ids_instead_of_returning_a_zero_row() {
    Article article = saveArticle("known article");

    List<ArticleFavoriteCount> counts =
        articleFavoritesReadService.articlesFavoriteCount(
            Arrays.asList(article.getId(), "does-not-exist"));

    Assertions.assertEquals(1, counts.size());
    Assertions.assertEquals(article.getId(), counts.get(0).getId());
  }

  @Test
  public void should_fail_on_an_empty_id_batch() {
    Assertions.assertThrows(
        Exception.class,
        () -> articleFavoritesReadService.articlesFavoriteCount(new ArrayList<>()));
  }

  @Test
  public void should_handle_a_large_id_batch() {
    List<String> ids = new ArrayList<>();
    Article favorited = saveArticle("favorited article");
    articleFavoriteRepository.save(new ArticleFavorite(favorited.getId(), user.getId()));
    ids.add(favorited.getId());
    for (int i = 0; i < 600; i++) {
      ids.add("missing-article-" + i);
    }

    List<ArticleFavoriteCount> counts = articleFavoritesReadService.articlesFavoriteCount(ids);
    Map<String, Integer> countMap =
        counts.stream()
            .collect(Collectors.toMap(ArticleFavoriteCount::getId, ArticleFavoriteCount::getCount));

    Assertions.assertEquals(1, countMap.size());
    Assertions.assertEquals(1, countMap.get(favorited.getId()));

    Set<String> favorites = articleFavoritesReadService.userFavorites(ids, user);
    Assertions.assertEquals(Collections.singleton(favorited.getId()), favorites);
  }

  @Test
  public void should_return_only_the_articles_favorited_by_the_given_user() {
    User anotherUser = new User("other@test.com", "other", "123", "", "");
    userRepository.save(anotherUser);
    Article mine = saveArticle("mine");
    Article theirs = saveArticle("theirs");
    articleFavoriteRepository.save(new ArticleFavorite(mine.getId(), user.getId()));
    articleFavoriteRepository.save(new ArticleFavorite(theirs.getId(), anotherUser.getId()));

    Set<String> favorites =
        articleFavoritesReadService.userFavorites(
            Arrays.asList(mine.getId(), theirs.getId()), user);

    Assertions.assertEquals(Collections.singleton(mine.getId()), favorites);
  }

  @Test
  public void should_fail_on_an_empty_id_batch_for_user_favorites() {
    Assertions.assertThrows(
        Exception.class, () -> articleFavoritesReadService.userFavorites(new ArrayList<>(), user));
  }

  @Test
  public void should_count_a_single_article_and_report_membership() {
    Article article = saveArticle("single");
    Assertions.assertEquals(0, articleFavoritesReadService.articleFavoriteCount(article.getId()));
    Assertions.assertFalse(
        articleFavoritesReadService.isUserFavorite(user.getId(), article.getId()));

    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), user.getId()));

    Assertions.assertEquals(1, articleFavoritesReadService.articleFavoriteCount(article.getId()));
    Assertions.assertTrue(
        articleFavoritesReadService.isUserFavorite(user.getId(), article.getId()));
  }

  @Test
  public void should_count_zero_for_an_unknown_article_id() {
    Assertions.assertEquals(0, articleFavoritesReadService.articleFavoriteCount("nope"));
  }

  private Article saveArticle(String title) {
    Article article = new Article(title, "desc", "body", Arrays.asList("java"), user.getId());
    articleRepository.save(article);
    return article;
  }
}
