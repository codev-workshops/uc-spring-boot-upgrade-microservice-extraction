package io.spring.api;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.spring.JacksonCustomizations;
import io.spring.api.security.WebSecurityConfig;
import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.Page;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ProfileData;
import java.util.ArrayList;
import java.util.Collections;
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

@WebMvcTest(ArticlesApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class CursorArticlesApiTest extends TestWithCurrentUser {
  @MockBean private ArticleQueryService articleQueryService;

  @MockBean private ArticleCommandService articleCommandService;

  @Autowired private MockMvc mvc;

  private ArticleData first;
  private ArticleData second;

  @Override
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    first = articleAt("1", new DateTime(2000, DateTimeZone.UTC));
    second = articleAt("2", new DateTime(1000, DateTimeZone.UTC));
  }

  @Test
  public void should_get_articles_next_page_with_cursor() throws Exception {
    when(articleQueryService.findRecentArticlesWithCursor(
            eq("java"), eq("author"), eq("fan"), any(), eq(null)))
        .thenReturn(new CursorPager<>(asList(first, second), Direction.NEXT, true));

    given()
        .param("first", 2)
        .param("after", "3000")
        .param("tag", "java")
        .param("author", "author")
        .param("favorited", "fan")
        .when()
        .get("/articles")
        .then()
        .statusCode(200)
        .body("articles", hasSize(2))
        .body("articles[0].slug", equalTo(first.getSlug()))
        .body("articles[1].slug", equalTo(second.getSlug()))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("1000"))
        .body("hasNext", equalTo(true))
        .body("hasPrevious", equalTo(false));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService)
        .findRecentArticlesWithCursor(
            eq("java"), eq("author"), eq("fan"), captor.capture(), eq(null));
    CursorPageParameter<DateTime> page = captor.getValue();
    assertPage(page, Direction.NEXT, 2, DateTimeCursor.parse("3000"));
    verify(articleQueryService, never()).findRecentArticles(any(), any(), any(), any(), any());
  }

  @Test
  public void should_get_articles_previous_page_with_cursor() throws Exception {
    when(articleQueryService.findRecentArticlesWithCursor(
            eq(null), eq(null), eq(null), any(), eq(null)))
        .thenReturn(new CursorPager<>(asList(first, second), Direction.PREV, true));

    given()
        .param("last", 2)
        .param("before", "500")
        .when()
        .get("/articles")
        .then()
        .statusCode(200)
        .body("articles", hasSize(2))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("1000"))
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(true));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService)
        .findRecentArticlesWithCursor(eq(null), eq(null), eq(null), captor.capture(), eq(null));
    assertPage(captor.getValue(), Direction.PREV, 2, DateTimeCursor.parse("500"));
  }

  @Test
  public void should_get_first_page_without_cursor_value() throws Exception {
    when(articleQueryService.findRecentArticlesWithCursor(
            eq(null), eq(null), eq(null), any(), eq(null)))
        .thenReturn(new CursorPager<>(asList(first), Direction.NEXT, false));

    given()
        .param("first", 20)
        .when()
        .get("/articles")
        .then()
        .statusCode(200)
        .body("articles", hasSize(1))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("2000"))
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(false));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService)
        .findRecentArticlesWithCursor(eq(null), eq(null), eq(null), captor.capture(), eq(null));
    assertPage(captor.getValue(), Direction.NEXT, 20, null);
  }

  @Test
  public void should_get_empty_page_with_null_cursors() throws Exception {
    when(articleQueryService.findRecentArticlesWithCursor(
            eq(null), eq(null), eq(null), any(), eq(null)))
        .thenReturn(new CursorPager<>(new ArrayList<>(), Direction.NEXT, false));

    given()
        .param("first", 10)
        .param("after", "1")
        .when()
        .get("/articles")
        .then()
        .statusCode(200)
        .body("articles", hasSize(0))
        .body("startCursor", nullValue())
        .body("endCursor", nullValue())
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(false));
  }

  @Test
  public void should_keep_offset_paging_when_no_cursor_params() throws Exception {
    when(articleQueryService.findRecentArticles(
            eq(null), eq(null), eq(null), eq(new Page(0, 20)), eq(null)))
        .thenReturn(new ArticleDataList(asList(first, second), 2));

    RestAssuredMockMvc.when()
        .get("/articles")
        .then()
        .statusCode(200)
        .body("articles", hasSize(2))
        .body("articlesCount", equalTo(2));

    verify(articleQueryService, never())
        .findRecentArticlesWithCursor(any(), any(), any(), any(), any());
  }

  @Test
  public void should_get_feed_next_page_with_cursor() throws Exception {
    when(articleQueryService.findUserFeedWithCursor(eq(user), any()))
        .thenReturn(new CursorPager<>(asList(first, second), Direction.NEXT, true));

    given()
        .header("Authorization", "Token " + token)
        .param("first", 2)
        .param("after", "3000")
        .when()
        .get("/articles/feed")
        .then()
        .statusCode(200)
        .body("articles", hasSize(2))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("1000"))
        .body("hasNext", equalTo(true))
        .body("hasPrevious", equalTo(false));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService).findUserFeedWithCursor(eq(user), captor.capture());
    assertPage(captor.getValue(), Direction.NEXT, 2, DateTimeCursor.parse("3000"));
    verify(articleQueryService, never()).findUserFeed(any(), any());
  }

  @Test
  public void should_get_feed_previous_page_with_cursor() throws Exception {
    when(articleQueryService.findUserFeedWithCursor(eq(user), any()))
        .thenReturn(new CursorPager<>(Collections.singletonList(second), Direction.PREV, false));

    given()
        .header("Authorization", "Token " + token)
        .param("last", 1)
        .param("before", "2000")
        .when()
        .get("/articles/feed")
        .then()
        .statusCode(200)
        .body("articles", hasSize(1))
        .body("startCursor", equalTo("1000"))
        .body("endCursor", equalTo("1000"))
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(false));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(articleQueryService).findUserFeedWithCursor(eq(user), captor.capture());
    assertPage(captor.getValue(), Direction.PREV, 1, DateTimeCursor.parse("2000"));
  }

  @Test
  public void should_get_401_for_cursor_feed_without_login() throws Exception {
    given().param("first", 2).when().get("/articles/feed").then().statusCode(401);
  }

  private static void assertPage(
      CursorPageParameter<DateTime> page, Direction direction, int limit, DateTime cursor) {
    assertEquals(direction, page.getDirection());
    assertEquals(limit, page.getLimit());
    assertEquals(cursor, page.getCursor());
  }

  private ArticleData articleAt(String seed, DateTime updatedAt) {
    return new ArticleData(
        seed + "id",
        "title-" + seed,
        "title " + seed,
        "desc " + seed,
        "body " + seed,
        false,
        0,
        updatedAt,
        updatedAt,
        new ArrayList<>(),
        new ProfileData(user.getId(), user.getUsername(), user.getBio(), user.getImage(), false));
  }
}
