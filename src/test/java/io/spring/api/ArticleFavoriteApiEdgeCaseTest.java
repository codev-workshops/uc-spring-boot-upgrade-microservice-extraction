package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.ArticleQueryService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ProfileData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Edge cases of POST/DELETE /articles/{slug}/favorite, the first endpoints to be extracted. */
@WebMvcTest(ArticleFavoriteApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class ArticleFavoriteApiEdgeCaseTest extends TestWithCurrentUser {
  @Autowired private MockMvc mvc;

  @MockBean private ArticleFavoriteRepository articleFavoriteRepository;
  @MockBean private ArticleRepository articleRepository;
  @MockBean private ArticleQueryService articleQueryService;

  private Article article;

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    User author = new User("author@test.com", "author", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), author.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
    when(articleQueryService.findBySlug(eq(article.getSlug()), eq(user)))
        .thenReturn(Optional.of(articleData(author, false, 0)));
  }

  @Test
  public void should_get_404_when_favoriting_unknown_slug() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .post("/articles/{slug}/favorite", "no-such-slug")
        .then()
        .statusCode(404);

    verify(articleFavoriteRepository, never()).save(any());
  }

  @Test
  public void should_get_404_when_unfavoriting_unknown_slug() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/favorite", "no-such-slug")
        .then()
        .statusCode(404);

    verify(articleFavoriteRepository, never()).remove(any());
  }

  @Test
  public void should_get_401_when_anonymous_favorites_an_article() {
    given().when().post("/articles/{slug}/favorite", article.getSlug()).then().statusCode(401);
  }

  @Test
  public void should_accept_a_repeated_favorite_as_success() {
    for (int i = 0; i < 2; i++) {
      given()
          .header("Authorization", "Token " + token)
          .when()
          .post("/articles/{slug}/favorite", article.getSlug())
          .then()
          .statusCode(200)
          .body("article.id", equalTo(article.getId()));
    }

    verify(articleFavoriteRepository, times(2))
        .save(eq(new ArticleFavorite(article.getId(), user.getId())));
  }

  @Test
  public void should_treat_unfavorite_of_a_non_favorited_article_as_a_no_op() {
    when(articleFavoriteRepository.find(eq(article.getId()), eq(user.getId())))
        .thenReturn(Optional.empty());

    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/favorite", article.getSlug())
        .then()
        .statusCode(200)
        .body("article.id", equalTo(article.getId()));

    verify(articleFavoriteRepository, never()).remove(any());
  }

  @Test
  public void should_serialize_zero_favorites_count_as_zero() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}/favorite", article.getSlug())
        .then()
        .statusCode(200)
        .body("article.favoritesCount", equalTo(0))
        .body("article.favorited", equalTo(false));
  }

  private ArticleData articleData(User author, boolean favorited, int favoritesCount) {
    DateTime now = new DateTime();
    return new ArticleData(
        article.getId(),
        article.getSlug(),
        article.getTitle(),
        article.getDescription(),
        article.getBody(),
        favorited,
        favoritesCount,
        now,
        now,
        new ArrayList<>(),
        new ProfileData(
            author.getId(), author.getUsername(), author.getBio(), author.getImage(), false));
  }
}
