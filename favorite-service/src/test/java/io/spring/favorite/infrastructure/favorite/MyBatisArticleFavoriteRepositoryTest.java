package io.spring.favorite.infrastructure.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.favorite.core.favorite.ArticleFavorite;
import io.spring.favorite.core.favorite.ArticleFavoriteRepository;
import io.spring.favorite.infrastructure.DbTestBase;
import io.spring.favorite.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.favorite.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MyBatisArticleFavoriteRepository.class)
public class MyBatisArticleFavoriteRepositoryTest extends DbTestBase {
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;
  @Autowired private ArticleFavoritesReadService readService;

  @Test
  public void should_save_and_fetch_favorite_success() {
    ArticleFavorite favorite = new ArticleFavorite("article-1", "user-1");
    articleFavoriteRepository.save(favorite);
    Optional<ArticleFavorite> found = articleFavoriteRepository.find("article-1", "user-1");
    assertTrue(found.isPresent());
    assertEquals(favorite, found.get());
  }

  @Test
  public void should_ignore_double_insert() {
    articleFavoriteRepository.save(new ArticleFavorite("article-1", "user-1"));
    articleFavoriteRepository.save(new ArticleFavorite("article-1", "user-1"));
    assertEquals(
        1, readService.articlesFavoriteCount(Arrays.asList("article-1")).get(0).getCount());
  }

  @Test
  public void should_remove_favorite_success() {
    articleFavoriteRepository.save(new ArticleFavorite("article-1", "user-1"));
    articleFavoriteRepository.remove(new ArticleFavorite("article-1", "user-1"));
    assertFalse(articleFavoriteRepository.find("article-1", "user-1").isPresent());
  }

  @Test
  public void should_remove_absent_favorite_as_noop() {
    articleFavoriteRepository.remove(new ArticleFavorite("article-x", "user-x"));
    assertFalse(articleFavoriteRepository.find("article-x", "user-x").isPresent());
  }
}
