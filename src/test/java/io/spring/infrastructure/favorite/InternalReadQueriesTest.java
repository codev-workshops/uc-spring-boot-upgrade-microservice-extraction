package io.spring.infrastructure.favorite;

import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({MyBatisArticleFavoriteRepository.class, MyBatisUserRepository.class})
public class InternalReadQueriesTest extends DbTestBase {
  @Autowired private ArticleFavoriteRepository articleFavoriteRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ArticleFavoritesReadService articleFavoritesReadService;
  @Autowired private UserReadService userReadService;

  @Test
  public void should_query_favorites_without_local_article_rows() {
    User user = new User("a@b.com", "fan", "123", "", "");
    userRepository.save(user);
    articleFavoriteRepository.save(new ArticleFavorite("remote-1", user.getId()));
    articleFavoriteRepository.save(new ArticleFavorite("remote-1", "someone-else"));

    var counts = articleFavoritesReadService.favoriteCountsByArticleIds(List.of("remote-1", "x"));
    Assertions.assertEquals(1, counts.size());
    Assertions.assertEquals(2, counts.get(0).getCount());

    Set<String> favorited =
        articleFavoritesReadService.favoritedArticleIdsOfUser(
            user.getId(), List.of("remote-1", "x"));
    Assertions.assertEquals(Set.of("remote-1"), favorited);

    Assertions.assertEquals(
        List.of("remote-1"), articleFavoritesReadService.articleIdsFavoritedBy("fan"));
  }

  @Test
  public void should_find_users_by_ids() {
    User one = new User("one@b.com", "one", "123", "", "");
    User two = new User("two@b.com", "two", "123", "", "");
    userRepository.save(one);
    userRepository.save(two);
    Assertions.assertEquals(2, userReadService.findByIds(List.of(one.getId(), two.getId())).size());
  }
}
