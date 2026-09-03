package io.spring.article.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.article.JacksonCustomizations;
import io.spring.article.api.exception.CustomizeExceptionHandler;
import io.spring.article.api.exception.DuplicatedArticleException;
import io.spring.article.api.exception.ResourceNotFoundException;
import io.spring.article.api.security.WebSecurityConfig;
import io.spring.article.application.ArticleCommandService;
import io.spring.article.application.ArticleQueryService;
import io.spring.article.application.CursorPageParameter;
import io.spring.article.application.Page;
import io.spring.article.application.data.ArticleData;
import io.spring.article.application.data.ArticleIdsData;
import io.spring.article.application.data.ArticleListData;
import io.spring.article.core.article.Article;
import io.spring.article.core.service.JwtService;
import io.spring.article.infrastructure.service.DefaultJwtService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ArticleInternalApi.class)
@Import({
  WebSecurityConfig.class,
  CustomizeExceptionHandler.class,
  DefaultJwtService.class,
  JacksonCustomizations.class
})
public class ArticleInternalApiTest {
  private static final DateTime T1 = new DateTime(2024, 1, 31, 10, 15, 30, 123, DateTimeZone.UTC);
  private static final ArticleData ROW =
      new ArticleData(
          "a-1", "hello-world", "Hello World", "d", "b", "user-1", T1, T1, Arrays.asList("java"));
  private static final String ROW_JSON =
      "{\"id\":\"a-1\",\"slug\":\"hello-world\",\"title\":\"Hello World\",\"description\":\"d\","
          + "\"body\":\"b\",\"userId\":\"user-1\",\"createdAt\":\"2024-01-31T10:15:30.123Z\","
          + "\"updatedAt\":\"2024-01-31T10:15:30.123Z\",\"tagList\":[\"java\"]}";
  private static final String NEW_BODY =
      "{\"id\":\"a-1\",\"slug\":\"hello-world\",\"title\":\"Hello World\",\"description\":\"d\","
          + "\"body\":\"b\",\"userId\":\"user-1\",\"createdAt\":\"2024-01-31T10:15:30.123Z\","
          + "\"updatedAt\":1706696130123,\"tags\":[{\"id\":\"t-1\",\"name\":\"java\"}]}";

  @Autowired private MockMvc mvc;
  @Autowired private JwtService jwtService;
  @MockBean private ArticleQueryService queryService;
  @MockBean private ArticleCommandService commandService;

  private String token;

  @BeforeEach
  public void setUp() {
    RestAssuredMockMvc.mockMvc(mvc);
    token = jwtService.toToken("user-1");
  }

  @Test
  public void get_by_id_renders_row_envelope_with_iso_utc_timestamps() {
    when(queryService.findById("a-1")).thenReturn(Optional.of(ROW));
    String json =
        given().when().get("/internal/articles/a-1").then().statusCode(200).extract().asString();
    assertEquals("{\"article\":" + ROW_JSON + "}", json);
  }

  @Test
  public void get_by_id_is_404_for_unknown() {
    when(queryService.findById("nope")).thenReturn(Optional.empty());
    given()
        .when()
        .get("/internal/articles/nope")
        .then()
        .statusCode(404)
        .body("errors.body[0]", equalTo("article not found"));
  }

  @Test
  public void get_by_slug_renders_row_or_404() {
    when(queryService.findBySlug("hello-world")).thenReturn(Optional.of(ROW));
    when(queryService.findBySlug("nope")).thenReturn(Optional.empty());
    given()
        .when()
        .get("/internal/articles/by-slug/hello-world")
        .then()
        .statusCode(200)
        .body("article.slug", equalTo("hello-world"));
    given().when().get("/internal/articles/by-slug/nope").then().statusCode(404);
  }

  @Test
  public void get_by_ids_splits_ids_and_returns_empty_for_blank() {
    when(queryService.findArticles(Arrays.asList("a-1", "a-2")))
        .thenReturn(Collections.singletonList(ROW));
    when(queryService.findArticles(Collections.emptyList())).thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/articles?ids=a-1, a-2")
        .then()
        .statusCode(200)
        .body("articles", hasSize(1))
        .body("articles[0].id", equalTo("a-1"));
    given()
        .when()
        .get("/internal/articles?ids=")
        .then()
        .statusCode(200)
        .body("articles", hasSize(0));
    given().when().get("/internal/articles").then().statusCode(200).body("articles", hasSize(0));
  }

  @Test
  public void ids_passes_filters_and_page_and_renders_count() {
    ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
    when(queryService.findArticleIds(
            eq("java"), eq("user-1"), eq(Arrays.asList("a-1", "a-2")), page.capture()))
        .thenReturn(new ArticleIdsData(Arrays.asList("a-2", "a-1"), 7));
    String json =
        given()
            .when()
            .get("/internal/articles/ids?tag=java&authorId=user-1&ids=a-1,a-2&offset=5&limit=2")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals("{\"articleIds\":[\"a-2\",\"a-1\"],\"count\":7}", json);
    assertEquals(5, page.getValue().getOffset());
    assertEquals(2, page.getValue().getLimit());
  }

  @Test
  public void ids_without_ids_param_passes_null_allow_list_and_empty_ids_passes_empty_list() {
    when(queryService.findArticleIds(isNull(), isNull(), isNull(), any()))
        .thenReturn(new ArticleIdsData(Arrays.asList("a-1"), 1));
    when(queryService.findArticleIds(isNull(), isNull(), eq(Collections.emptyList()), any()))
        .thenReturn(new ArticleIdsData(Collections.emptyList(), 0));
    given().when().get("/internal/articles/ids").then().statusCode(200).body("count", equalTo(1));
    given()
        .when()
        .get("/internal/articles/ids?ids=")
        .then()
        .statusCode(200)
        .body("count", equalTo(0))
        .body("articleIds", hasSize(0));
  }

  @Test
  public void ids_cursor_passes_millis_cursor_limit_and_direction() {
    ArgumentCaptor<CursorPageParameter> page = ArgumentCaptor.forClass(CursorPageParameter.class);
    when(queryService.findArticleIdsWithCursor(
            eq("java"), isNull(), eq(Arrays.asList("a-1")), page.capture()))
        .thenReturn(Arrays.asList("a-1"));
    given()
        .when()
        .get(
            "/internal/articles/ids/cursor?tag=java&ids=a-1&limit=3&direction=prev&cursor=1706696130123")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(1));
    assertEquals(1706696130123L, page.getValue().getCursor().getMillis());
    assertEquals(3, page.getValue().getLimit());
    assertEquals(4, page.getValue().getQueryLimit());
    assertTrue(!page.getValue().isNext());
  }

  @Test
  public void ids_cursor_defaults_to_next_without_cursor() {
    ArgumentCaptor<CursorPageParameter> page = ArgumentCaptor.forClass(CursorPageParameter.class);
    when(queryService.findArticleIdsWithCursor(isNull(), isNull(), isNull(), page.capture()))
        .thenReturn(Collections.emptyList());
    given()
        .when()
        .get("/internal/articles/ids/cursor")
        .then()
        .statusCode(200)
        .body("articleIds", hasSize(0));
    assertNull(page.getValue().getCursor());
    assertTrue(page.getValue().isNext());
    assertEquals(20, page.getValue().getLimit());
  }

  @Test
  public void feed_passes_author_ids_and_page() {
    ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
    when(queryService.findUserFeed(eq(Arrays.asList("u-1", "u-2")), page.capture()))
        .thenReturn(new ArticleListData(Collections.singletonList(ROW), 3));
    when(queryService.findUserFeed(eq(Collections.emptyList()), any()))
        .thenReturn(new ArticleListData(Collections.emptyList(), 0));
    String json =
        given()
            .when()
            .get("/internal/articles/feed?authorIds=u-1,u-2&offset=1&limit=1")
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals("{\"articles\":[" + ROW_JSON + "],\"count\":3}", json);
    assertEquals(1, page.getValue().getOffset());
    given()
        .when()
        .get("/internal/articles/feed")
        .then()
        .statusCode(200)
        .body("articles", hasSize(0))
        .body("count", equalTo(0));
  }

  @Test
  public void feed_cursor_passes_author_ids_and_cursor() {
    ArgumentCaptor<CursorPageParameter> page = ArgumentCaptor.forClass(CursorPageParameter.class);
    when(queryService.findUserFeedWithCursor(eq(Arrays.asList("u-1")), page.capture()))
        .thenReturn(Collections.singletonList(ROW));
    given()
        .when()
        .get("/internal/articles/feed/cursor?authorIds=u-1&limit=2&direction=next&cursor=1000")
        .then()
        .statusCode(200)
        .body("articles", hasSize(1))
        .body("articles[0].createdAt", equalTo("2024-01-31T10:15:30.123Z"));
    assertEquals(1000L, page.getValue().getCursor().getMillis());
    assertTrue(page.getValue().isNext());
  }

  @Test
  public void create_is_201_and_stores_caller_supplied_fields_verbatim() {
    ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
    when(commandService.create(captor.capture()))
        .thenReturn(new ArticleCommandService.CreateResult(ROW, true));
    String json =
        given()
            .header("Authorization", "Token " + token)
            .contentType("application/json")
            .body(NEW_BODY)
            .post("/internal/articles")
            .then()
            .statusCode(201)
            .extract()
            .asString();
    assertEquals("{\"article\":" + ROW_JSON + "}", json);
    Article sent = captor.getValue();
    assertEquals("a-1", sent.getId());
    assertEquals("hello-world", sent.getSlug());
    assertEquals("user-1", sent.getUserId());
    assertEquals(T1.getMillis(), sent.getCreatedAt().getMillis());
    assertEquals(T1.getMillis(), sent.getUpdatedAt().getMillis());
    assertEquals("t-1", sent.getTags().get(0).getId());
    assertEquals("java", sent.getTags().get(0).getName());
  }

  @Test
  public void create_is_200_when_same_id_already_exists() {
    when(commandService.create(any()))
        .thenReturn(new ArticleCommandService.CreateResult(ROW, false));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body(NEW_BODY)
        .post("/internal/articles")
        .then()
        .statusCode(200)
        .body("article.id", equalTo("a-1"));
  }

  @Test
  public void create_is_422_title_envelope_on_slug_clash() {
    when(commandService.create(any())).thenThrow(new DuplicatedArticleException());
    String json =
        given()
            .header("Authorization", "Token " + token)
            .contentType("application/json")
            .body(NEW_BODY)
            .post("/internal/articles")
            .then()
            .statusCode(422)
            .extract()
            .asString();
    assertEquals("{\"errors\":{\"title\":[\"article name exists\"]}}", json);
  }

  @Test
  public void create_is_403_when_token_subject_differs_from_user_id() {
    given()
        .header("Authorization", "Token " + jwtService.toToken("someone-else"))
        .contentType("application/json")
        .body(NEW_BODY)
        .post("/internal/articles")
        .then()
        .statusCode(403);
    verify(commandService, never()).create(any());
  }

  @Test
  public void create_is_401_without_or_with_invalid_token() {
    given()
        .contentType("application/json")
        .body(NEW_BODY)
        .post("/internal/articles")
        .then()
        .statusCode(401)
        .body("errors.body[0]", equalTo("missing or invalid token"));
    given()
        .header("Authorization", "Token garbage")
        .contentType("application/json")
        .body(NEW_BODY)
        .post("/internal/articles")
        .then()
        .statusCode(401);
    verify(commandService, never()).create(any());
  }

  @Test
  public void create_is_422_on_blank_title_or_unreadable_body() {
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"title\":\"\",\"description\":\"d\",\"body\":\"b\",\"userId\":\"user-1\"}")
        .post("/internal/articles")
        .then()
        .statusCode(422)
        .body("errors.body[0]", equalTo("title can't be empty"));
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("not json")
        .post("/internal/articles")
        .then()
        .statusCode(422);
  }

  @Test
  public void update_is_200_and_forwards_fields() {
    when(commandService.update("a-1", "New", null, "")).thenReturn(ROW);
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"title\":\"New\",\"body\":\"\"}")
        .put("/internal/articles/a-1")
        .then()
        .statusCode(200)
        .body("article.id", equalTo("a-1"));
  }

  @Test
  public void update_is_404_422_and_401() {
    when(commandService.update(eq("nope"), any(), any(), any()))
        .thenThrow(new ResourceNotFoundException());
    when(commandService.update(eq("a-1"), any(), any(), any()))
        .thenThrow(new DuplicatedArticleException());
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"title\":\"New\"}")
        .put("/internal/articles/nope")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", "Token " + token)
        .contentType("application/json")
        .body("{\"title\":\"Taken\"}")
        .put("/internal/articles/a-1")
        .then()
        .statusCode(422)
        .body("errors.title[0]", equalTo("article name exists"));
    given()
        .contentType("application/json")
        .body("{\"title\":\"New\"}")
        .put("/internal/articles/a-1")
        .then()
        .statusCode(401);
  }

  @Test
  public void delete_is_204_idempotent_and_401_without_token() {
    given()
        .header("Authorization", "Token " + token)
        .delete("/internal/articles/a-1")
        .then()
        .statusCode(204);
    given()
        .header("Authorization", "Token " + token)
        .delete("/internal/articles/a-1")
        .then()
        .statusCode(204);
    verify(commandService, org.mockito.Mockito.times(2)).delete("a-1");
    given().delete("/internal/articles/a-1").then().statusCode(401);
  }

  @Test
  public void get_endpoints_are_anonymous() {
    when(queryService.findById("a-1")).thenReturn(Optional.of(ROW));
    when(queryService.findBySlug("hello-world")).thenReturn(Optional.of(ROW));
    List<String> paths =
        Arrays.asList(
            "/internal/articles/a-1",
            "/internal/articles/by-slug/hello-world",
            "/internal/articles?ids=a-1",
            "/internal/articles/ids",
            "/internal/articles/ids/cursor",
            "/internal/articles/feed",
            "/internal/articles/feed/cursor");
    for (String path : paths) {
      given().when().get(path).then().statusCode(200);
    }
  }
}
