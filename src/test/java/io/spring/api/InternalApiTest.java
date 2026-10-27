package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.InternalQueryService;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.UserData;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({InternalApi.class})
@Import({WebSecurityConfig.class, JacksonCustomizations.class, InternalQueryService.class})
public class InternalApiTest extends TestWithCurrentUser {
  @Autowired private MockMvc mvc;

  @MockBean private UserRelationshipQueryService userRelationshipQueryService;

  @MockBean private ArticleFavoritesReadService articleFavoritesReadService;

  @Override
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
  }

  @Test
  public void should_return_current_user_for_valid_token() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .get("/internal/me")
        .then()
        .statusCode(200)
        .body("user.id", equalTo(user.getId()))
        .body("user.username", equalTo(username));
  }

  @Test
  public void should_return_401_for_current_user_without_token() {
    given().when().get("/internal/me").then().statusCode(401);
  }

  @Test
  public void should_return_followed_user_ids() {
    when(userRelationshipQueryService.followedUsers(eq(user.getId())))
        .thenReturn(List.of("a", "b"));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .get("/internal/me/following")
        .then()
        .statusCode(200)
        .body("userIds", contains("a", "b"));
  }

  @Test
  public void should_return_profiles_by_ids_with_following_flag() {
    UserData other = new UserData("other-id", "o@x.com", "other", "bio", "img");
    when(userReadService.findByIds(eq(List.of("other-id")))).thenReturn(List.of(other));
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), eq(List.of("other-id"))))
        .thenReturn(Set.of("other-id"));
    given()
        .header("Authorization", "Token " + token)
        .queryParam("ids", "other-id")
        .when()
        .get("/internal/profiles")
        .then()
        .statusCode(200)
        .body("profiles", hasSize(1))
        .body("profiles[0].id", equalTo("other-id"))
        .body("profiles[0].username", equalTo("other"))
        .body("profiles[0].following", equalTo(true));
  }

  @Test
  public void should_return_empty_profiles_for_unknown_username_anonymously() {
    given()
        .queryParam("username", "missing")
        .when()
        .get("/internal/profiles")
        .then()
        .statusCode(200)
        .body("profiles", hasSize(0));
    verify(userRelationshipQueryService, never())
        .followingAuthors(ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  public void should_return_favorites_with_defaults_for_missing_articles() {
    when(articleFavoritesReadService.favoriteCountsByArticleIds(eq(List.of("a1", "a2"))))
        .thenReturn(List.of(new ArticleFavoriteCount("a1", 3)));
    when(articleFavoritesReadService.favoritedArticleIdsOfUser(
            eq(user.getId()), eq(List.of("a1", "a2"))))
        .thenReturn(Set.of("a1"));
    given()
        .header("Authorization", "Token " + token)
        .queryParam("articleIds", "a1", "a2")
        .when()
        .get("/internal/favorites")
        .then()
        .statusCode(200)
        .body("favorites[0].articleId", equalTo("a1"))
        .body("favorites[0].favoritesCount", equalTo(3))
        .body("favorites[0].favorited", equalTo(true))
        .body("favorites[1].articleId", equalTo("a2"))
        .body("favorites[1].favoritesCount", equalTo(0))
        .body("favorites[1].favorited", equalTo(false));
  }

  @Test
  public void should_return_article_ids_favorited_by_user() {
    when(articleFavoritesReadService.articleIdsFavoritedBy(eq("jane"))).thenReturn(List.of("a1"));
    given()
        .when()
        .get("/internal/favorites/by-user/jane")
        .then()
        .statusCode(200)
        .body("articleIds", contains("a1"));
  }
}
