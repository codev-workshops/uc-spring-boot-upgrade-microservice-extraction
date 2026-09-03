package io.spring.infrastructure.favorite;

import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.mybatis.mapper.ArticleFavoriteMapper;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Edge cases of the favorite write path. Favorite is the first domain to be extracted, so the
 * behaviour asserted here is the contract the extracted service has to reproduce.
 */
@Import({MyBatisArticleFavoriteRepository.class})
public class ArticleFavoriteRepositoryEdgeCaseTest extends DbTestBase {
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;

  @Autowired private ArticleFavoriteMapper articleFavoriteMapper;

  @Test
  public void should_be_idempotent_when_favoriting_twice() {
    ArticleFavorite articleFavorite = new ArticleFavorite("article-1", "user-1");
    articleFavoriteRepository.save(articleFavorite);
    articleFavoriteRepository.save(new ArticleFavorite("article-1", "user-1"));

    Assertions.assertTrue(articleFavoriteRepository.find("article-1", "user-1").isPresent());
    Assertions.assertNotNull(articleFavoriteMapper.find("article-1", "user-1"));
  }

  @Test
  public void should_reject_duplicate_insert_at_mapper_level() {
    articleFavoriteMapper.insert(new ArticleFavorite("article-2", "user-2"));

    UncategorizedSQLException exception =
        Assertions.assertThrows(
            UncategorizedSQLException.class,
            () -> articleFavoriteMapper.insert(new ArticleFavorite("article-2", "user-2")));

    Assertions.assertTrue(exception.getMessage().contains("UNIQUE constraint failed"));
  }

  @Test
  public void should_ignore_remove_of_a_favorite_that_does_not_exist() {
    articleFavoriteRepository.remove(new ArticleFavorite("article-3", "user-3"));

    Assertions.assertFalse(articleFavoriteRepository.find("article-3", "user-3").isPresent());
  }

  @Test
  public void should_only_remove_the_favorite_of_the_given_user() {
    articleFavoriteRepository.save(new ArticleFavorite("article-4", "user-a"));
    articleFavoriteRepository.save(new ArticleFavorite("article-4", "user-b"));

    articleFavoriteRepository.remove(new ArticleFavorite("article-4", "user-a"));

    Assertions.assertFalse(articleFavoriteRepository.find("article-4", "user-a").isPresent());
    Assertions.assertTrue(articleFavoriteRepository.find("article-4", "user-b").isPresent());
  }
}
