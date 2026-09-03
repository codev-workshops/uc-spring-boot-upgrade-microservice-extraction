package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.ArticleQueryService;
import io.spring.application.article.ArticleCommandService;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization contract of the article write endpoints. Complements ArticleApiTest, which covers
 * the 403 cases; this class pins the 401 (anonymous) and 404 (missing article) behaviour that later
 * extraction phases must preserve.
 */
@WebMvcTest(ArticleApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class ArticleAuthorizationApiTest extends TestWithCurrentUser {
  @Autowired private MockMvc mvc;

  @MockBean private ArticleQueryService articleQueryService;
  @MockBean private ArticleRepository articleRepository;
  @MockBean private ArticleCommandService articleCommandService;

  private Article othersArticle;

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    User anotherUser = new User("other@test.com", "other", "123", "", "");
    othersArticle =
        new Article("title", "desc", "body", Arrays.asList("java"), anotherUser.getId());
    when(articleRepository.findBySlug(eq(othersArticle.getSlug())))
        .thenReturn(Optional.of(othersArticle));
  }

  @Test
  public void should_get_401_when_anonymous_updates_article() {
    given()
        .contentType("application/json")
        .body(updateParam())
        .when()
        .put("/articles/{slug}", othersArticle.getSlug())
        .then()
        .statusCode(401);
  }

  @Test
  public void should_get_401_when_anonymous_deletes_article() {
    given().when().delete("/articles/{slug}", othersArticle.getSlug()).then().statusCode(401);
  }

  @Test
  public void should_get_404_when_updating_unknown_article() {
    given()
        .contentType("application/json")
        .header("Authorization", "Token " + token)
        .body(updateParam())
        .when()
        .put("/articles/{slug}", "no-such-slug")
        .then()
        .statusCode(404);
  }

  @Test
  public void should_get_404_when_deleting_unknown_article() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}", "no-such-slug")
        .then()
        .statusCode(404);
  }

  @Test
  public void should_not_touch_repository_when_delete_is_forbidden() {
    given()
        .header("Authorization", "Token " + token)
        .when()
        .delete("/articles/{slug}", othersArticle.getSlug())
        .then()
        .statusCode(403);

    verify(articleRepository, never()).remove(eq(othersArticle));
  }

  private Map<String, Object> updateParam() {
    return new HashMap<String, Object>() {
      {
        put(
            "article",
            new HashMap<String, Object>() {
              {
                put("title", "new title");
                put("body", "new body");
                put("description", "new description");
              }
            });
      }
    };
  }
}
