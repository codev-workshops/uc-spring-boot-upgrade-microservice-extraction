package io.spring.application.favorite;

import io.spring.application.ArticleQueryService;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Favorite-related read contracts of ArticleQueryService: favorite counts, the anonymous reader,
 * and the favoritedBy filter. Phase 1 replaces the backing read service with a remote call, so
 * these results have to stay identical.
 */
@Import({
  ArticleQueryService.class,
  MyBatisUserRepository.class,
  MyBatisArticleRepository.class,
  MyBatisArticleFavoriteRepository.class
})
public class FavoriteQueryServiceTest extends DbTestBase {
  @Autowired private ArticleQueryService queryService;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  private User author;
  private User reader;
  private Article article;

  @BeforeEach
  public void setUp() {
    author = new User("author@test.com", "author", "123", "", "");
    reader = new User("reader@test.com", "reader", "123", "", "");
    userRepository.save(author);
    userRepository.save(reader);
    article = new Article("title", "desc", "body", Arrays.asList("java"), author.getId());
    articleRepository.save(article);
  }

  @Test
  public void should_report_zero_favorites_for_a_never_favorited_article() {
    ArticleData single = queryService.findBySlug(article.getSlug(), reader).get();
    Assertions.assertEquals(0, single.getFavoritesCount());
    Assertions.assertFalse(single.isFavorited());

    ArticleData listed =
        queryService
            .findRecentArticles(null, null, null, new Page(), reader)
            .getArticleDatas()
            .get(0);
    Assertions.assertEquals(0, listed.getFavoritesCount());
    Assertions.assertFalse(listed.isFavorited());
  }

  @Test
  public void should_report_favorites_count_but_not_favorited_for_anonymous_reader() {
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), reader.getId()));

    ArticleData single = queryService.findBySlug(article.getSlug(), null).get();
    Assertions.assertFalse(single.isFavorited());
    Assertions.assertEquals(0, single.getFavoritesCount());

    ArticleData listed =
        queryService
            .findRecentArticles(null, null, null, new Page(), null)
            .getArticleDatas()
            .get(0);
    Assertions.assertFalse(listed.isFavorited());
    Assertions.assertEquals(1, listed.getFavoritesCount());
  }

  @Test
  public void should_not_double_count_an_idempotent_double_favorite() {
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), reader.getId()));
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), reader.getId()));

    ArticleData single = queryService.findBySlug(article.getSlug(), reader).get();
    Assertions.assertEquals(1, single.getFavoritesCount());
    Assertions.assertTrue(single.isFavorited());
  }

  @Test
  public void should_return_empty_for_unknown_slug() {
    Optional<ArticleData> optional = queryService.findBySlug("no-such-slug", reader);
    Assertions.assertFalse(optional.isPresent());
  }

  @Test
  public void should_filter_articles_by_favorited_by() {
    Article otherArticle =
        new Article("other title", "desc", "body", Arrays.asList("java"), author.getId());
    articleRepository.save(otherArticle);
    articleFavoriteRepository.save(new ArticleFavorite(article.getId(), reader.getId()));

    ArticleDataList favoritedByReader =
        queryService.findRecentArticles(null, null, reader.getUsername(), new Page(), reader);
    Assertions.assertEquals(1, favoritedByReader.getCount());
    Assertions.assertEquals(article.getId(), favoritedByReader.getArticleDatas().get(0).getId());
    Assertions.assertTrue(favoritedByReader.getArticleDatas().get(0).isFavorited());

    ArticleDataList favoritedByAuthor =
        queryService.findRecentArticles(null, null, author.getUsername(), new Page(), reader);
    Assertions.assertEquals(0, favoritedByAuthor.getCount());
    Assertions.assertEquals(0, favoritedByAuthor.getArticleDatas().size());

    ArticleDataList favoritedByUnknownUser =
        queryService.findRecentArticles(null, null, "ghost", new Page(), reader);
    Assertions.assertEquals(0, favoritedByUnknownUser.getCount());
  }
}
