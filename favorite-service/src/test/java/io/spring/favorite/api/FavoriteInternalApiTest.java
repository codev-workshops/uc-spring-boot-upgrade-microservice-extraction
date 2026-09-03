package io.spring.favorite.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.favorite.api.exception.CustomizeExceptionHandler;
import io.spring.favorite.api.security.WebSecurityConfig;
import io.spring.favorite.application.FavoriteCommandService;
import io.spring.favorite.application.FavoriteQueryService;
import io.spring.favorite.application.data.ArticleFavoriteCount;
import io.spring.favorite.application.data.FavoriteData;
import io.spring.favorite.application.data.UserFavorites;
import io.spring.favorite.core.service.JwtService;
import io.spring.favorite.infrastructure.service.DefaultJwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FavoriteInternalApi.class)
@Import({WebSecurityConfig.class, CustomizeExceptionHandler.class, DefaultJwtService.class})
public class FavoriteInternalApiTest {
  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;
  @MockBean private FavoriteQueryService favoriteQueryService;
  @MockBean private FavoriteCommandService favoriteCommandService;

  private String token;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    token = jwtService.toToken("user-1");
  }

  @Test
  public void counts_returns_one_entry_per_id_in_order() {
    when(favoriteQueryService.articlesFavoriteCount(Arrays.asList("a", "b")))
        .thenReturn(
            Arrays.asList(new ArticleFavoriteCount("a", 2), new ArticleFavoriteCount("b", 0)));
    given()
        .contentType("application/json")
        .body("{\"articleIds\":[\"a\",\"b\"]}")
        .when()
        .post("/internal/favorites/counts")
        .then()
        .statusCode(200)
        .body("counts", hasSize(2))
        .body("counts[0].articleId", equalTo("a"))
        .body("counts[0].count", equalTo(2))
        .body("counts[1].articleId", equalTo("b"))
        .body("counts[1].count", equalTo(0));
  }

  @Test
  public void counts_with_empty_batch_returns_empty_list() {
    when(favoriteQueryService.articlesFavoriteCount(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    given()
        .contentType("application/json")
        .body("{\"articleIds\":[]}")
        .when()
        .post("/internal/favorites/counts")
        .then()
        .statusCode(200)
        .body("counts", hasSize(0));
  }

  @Test
  public void counts_rejects_more_than_500_ids_with_422() {
    List<String> ids =
        IntStream.range(0, 501).mapToObj(i -> "\"a" + i + "\"").collect(Collectors.toList());
    given()
        .contentType("application/json")
        .body("{\"articleIds\":[" + String.join(",", ids) + "]}")
        .when()
        .post("/internal/favorites/counts")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("articleIds batch too large: 501 > 500"));
    verify(favoriteQueryService, never()).articlesFavoriteCount(anyList());
  }

  @Test
  public void counts_rejects_missing_article_ids_with_422() {
    given()
        .contentType("application/json")
        .body("{}")
        .when()
        .post("/internal/favorites/counts")
        .then()
        .statusCode(422)
        .body("errors.body", hasSize(1));
    given()
        .contentType("application/json")
        .body("not json")
        .when()
        .post("/internal/favorites/counts")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("invalid request body"));
  }

  @Test
  public void query_returns_favorited_subset() {
    when(favoriteQueryService.userFavorites("u", Arrays.asList("a", "b")))
        .thenReturn(new UserFavorites("u", Arrays.asList("a")));
    given()
        .contentType("application/json")
        .body("{\"userId\":\"u\",\"articleIds\":[\"a\",\"b\"]}")
        .when()
        .post("/internal/favorites/query")
        .then()
        .statusCode(200)
        .body("userId", equalTo("u"))
        .body("articleIds", equalTo(Arrays.asList("a")));
  }

  @Test
  public void query_rejects_blank_user_with_422() {
    given()
        .contentType("application/json")
        .body("{\"userId\":\"\",\"articleIds\":[\"a\"]}")
        .when()
        .post("/internal/favorites/query")
        .then()
        .statusCode(422)
        .body("errors.body", hasSize(1));
  }

  @Test
  public void by_user_returns_article_ids() {
    when(favoriteQueryService.articleIdsFavoritedBy("u"))
        .thenReturn(new UserFavorites("u", Arrays.asList("a", "c")));
    given()
        .when()
        .get("/internal/favorites/by-user/u/article-ids")
        .then()
        .statusCode(200)
        .body("userId", equalTo("u"))
        .body("articleIds", equalTo(Arrays.asList("a", "c")));
  }

  @Test
  public void put_favorites_for_token_owner() {
    when(favoriteCommandService.favorite("a", "user-1"))
        .thenReturn(new FavoriteData("a", "user-1", true));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(200)
        .body("articleId", equalTo("a"))
        .body("userId", equalTo("user-1"))
        .body("favorited", equalTo(true));
  }

  @Test
  public void put_without_token_is_401() {
    given()
        .when()
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(401)
        .body("errors.body[0]", equalTo("missing or invalid token"));
    verify(favoriteCommandService, never()).favorite(eq("a"), eq("user-1"));
  }

  @Test
  public void put_with_invalid_token_is_401() {
    given()
        .header("Authorization", "Token not-a-jwt")
        .when()
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(401);
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .put("/internal/favorites/a/user-1")
        .then()
        .statusCode(401);
  }

  @Test
  public void put_for_other_user_is_403() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .put("/internal/favorites/a/user-2")
        .then()
        .statusCode(403)
        .body("errors.body[0]", equalTo("token subject does not match userId"));
    verify(favoriteCommandService, never()).favorite(eq("a"), eq("user-2"));
  }

  @Test
  public void delete_is_204_for_token_owner() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/internal/favorites/a/user-1")
        .then()
        .statusCode(204);
    verify(favoriteCommandService).unfavorite("a", "user-1");
  }

  @Test
  public void delete_without_token_is_401_and_for_other_user_403() {
    given().when().delete("/internal/favorites/a/user-1").then().statusCode(401);
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/internal/favorites/a/user-2")
        .then()
        .statusCode(403);
    verify(favoriteCommandService, never()).unfavorite(eq("a"), eq("user-2"));
  }

  @Test
  public void error_envelope_shape_matches_monolith() {
    Map<String, Object> expected = new HashMap<>();
    expected.put("body", Collections.singletonList("token subject does not match userId"));
    given()
        .header("Authorization", "Token " + token)
        .when()
        .put("/internal/favorites/a/user-2")
        .then()
        .body("errors", equalTo(expected));
  }
}
