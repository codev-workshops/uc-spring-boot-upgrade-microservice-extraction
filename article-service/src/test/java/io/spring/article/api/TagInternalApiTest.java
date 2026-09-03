package io.spring.article.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.article.api.exception.CustomizeExceptionHandler;
import io.spring.article.api.security.WebSecurityConfig;
import io.spring.article.application.TagCommandService;
import io.spring.article.application.TagQueryService;
import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.core.service.JwtService;
import io.spring.article.core.tag.Tag;
import io.spring.article.infrastructure.service.DefaultJwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TagInternalApi.class)
@Import({WebSecurityConfig.class, CustomizeExceptionHandler.class, DefaultJwtService.class})
public class TagInternalApiTest {
  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;
  @MockBean private TagQueryService tagQueryService;
  @MockBean private TagCommandService tagCommandService;

  private String token;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    token = jwtService.toToken("user-1");
  }

  @Test
  public void tags_returns_names_in_service_order() {
    when(tagQueryService.allTags()).thenReturn(Arrays.asList("java", "spring-boot", "java"));
    String json = given().when().get("/internal/tags").then().statusCode(200).extract().asString();
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"tags\":[\"java\",\"spring-boot\",\"java\"]}", json);
  }

  @Test
  public void tags_is_empty_list_when_none() {
    when(tagQueryService.allTags()).thenReturn(Collections.emptyList());
    given().when().get("/internal/tags").then().statusCode(200).body("tags", hasSize(0));
  }

  @Test
  public void article_tags_splits_ids_and_renders_one_entry_per_id() {
    when(tagQueryService.findArticleTags(Arrays.asList("a", "b")))
        .thenReturn(
            Arrays.asList(
                new ArticleTagsData("a", Arrays.asList("java", "sql")),
                new ArticleTagsData("b", Collections.emptyList())));
    String json =
        given()
            .when()
            .get("/internal/articles/tags?articleIds=a, b")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"articleTags\":[{\"articleId\":\"a\",\"tagList\":[\"java\",\"sql\"]},"
            + "{\"articleId\":\"b\",\"tagList\":[]}]}",
        json);
  }

  @Test
  public void article_tags_with_empty_or_missing_ids_is_empty_without_query() {
    when(tagQueryService.findArticleTags(Collections.emptyList()))
        .thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/articles/tags?articleIds=")
        .then()
        .statusCode(200)
        .body("articleTags", hasSize(0));
    given()
        .when()
        .get("/internal/articles/tags")
        .then()
        .statusCode(200)
        .body("articleTags", hasSize(0));
    given()
        .when()
        .get("/internal/articles/tags?articleIds=,,")
        .then()
        .statusCode(200)
        .body("articleTags", hasSize(0));
  }

  @Test
  public void article_ids_by_tag_returns_list_or_empty_for_unknown() {
    when(tagQueryService.findArticleIdsByTagName("java")).thenReturn(Arrays.asList("a-1", "a-4"));
    when(tagQueryService.findArticleIdsByTagName("nope")).thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/tags/java/article-ids")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(2))
        .body("articleIds[0]", equalTo("a-1"))
        .body("articleIds[1]", equalTo("a-4"));
    given()
        .when()
        .get("/internal/tags/nope/article-ids")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(0));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void put_tags_passes_caller_supplied_ids_and_returns_tag_list() {
    when(tagCommandService.setTags(eq("a"), any()))
        .thenReturn(new ArticleTagsData("a", Arrays.asList("java", "sql")));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"tags\":[{\"id\":\"t-1\",\"name\":\"java\"},{\"id\":\"t-2\",\"name\":\"sql\"}]}")
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(200)
        .body("articleId", equalTo("a"))
        .body("tagList", hasSize(2))
        .body("tagList[0]", equalTo("java"));
    ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
    verify(tagCommandService).setTags(eq("a"), tags.capture());
    org.junit.jupiter.api.Assertions.assertEquals(2, tags.getValue().size());
    org.junit.jupiter.api.Assertions.assertEquals("t-1", tags.getValue().get(0).getId());
    org.junit.jupiter.api.Assertions.assertEquals("java", tags.getValue().get(0).getName());
    org.junit.jupiter.api.Assertions.assertEquals("t-2", tags.getValue().get(1).getId());
  }

  @Test
  public void put_tags_with_empty_list_is_200() {
    when(tagCommandService.setTags(eq("a"), any()))
        .thenReturn(new ArticleTagsData("a", Collections.emptyList()));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"tags\":[]}")
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(200)
        .body("articleId", equalTo("a"))
        .body("tagList", hasSize(0));
  }

  @Test
  public void put_tags_rejects_blank_name_or_bad_json_with_422() {
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"tags\":[{\"id\":\"t-1\",\"name\":\"  \"}]}")
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("tags[0].name can't be empty"));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("not json")
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("invalid request body"));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{}")
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("tags can't be missing"));
    verify(tagCommandService, never()).setTags(any(), any());
  }

  @Test
  public void put_tags_without_or_with_invalid_token_is_401() {
    String body = "{\"tags\":[{\"id\":\"t-1\",\"name\":\"java\"}]}";
    given()
        .contentType("application/json")
        .body(body)
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(401)
        .body("errors.body[0]", equalTo("missing or invalid token"));
    given()
        .header("Authorization", "Token not-a-jwt")
        .contentType("application/json")
        .body(body)
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(401);
    given()
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .body(body)
        .when()
        .put("/internal/articles/a/tags")
        .then()
        .statusCode(401);
    verify(tagCommandService, never()).setTags(any(), any());
  }
}
