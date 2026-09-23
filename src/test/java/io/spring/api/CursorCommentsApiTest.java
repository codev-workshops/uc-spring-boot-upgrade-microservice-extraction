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
import io.spring.application.CommentQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.CommentRepository;
import java.util.ArrayList;
import java.util.Arrays;
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

@WebMvcTest(CommentsApi.class)
@Import({WebSecurityConfig.class, JacksonCustomizations.class})
public class CursorCommentsApiTest extends TestWithCurrentUser {

  @MockBean private ArticleRepository articleRepository;

  @MockBean private CommentRepository commentRepository;

  @MockBean private CommentQueryService commentQueryService;

  @Autowired private MockMvc mvc;

  private Article article;
  private CommentData first;
  private CommentData second;

  @Override
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    RestAssuredMockMvc.mockMvc(mvc);
    article = new Article("title", "desc", "body", Arrays.asList("test", "java"), user.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
    first = commentAt("c1", new DateTime(2000, DateTimeZone.UTC));
    second = commentAt("c2", new DateTime(1000, DateTimeZone.UTC));
  }

  @Test
  public void should_get_comments_next_page_with_cursor() throws Exception {
    when(commentQueryService.findByArticleIdWithCursor(eq(article.getId()), eq(null), any()))
        .thenReturn(new CursorPager<>(asList(first, second), Direction.NEXT, true));

    given()
        .param("first", 2)
        .param("after", "3000")
        .when()
        .get("/articles/{slug}/comments", article.getSlug())
        .then()
        .statusCode(200)
        .body("comments", hasSize(2))
        .body("comments[0].id", equalTo("c1"))
        .body("comments[1].id", equalTo("c2"))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("1000"))
        .body("hasNext", equalTo(true))
        .body("hasPrevious", equalTo(false));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService)
        .findByArticleIdWithCursor(eq(article.getId()), eq(null), captor.capture());
    assertPage(captor.getValue(), Direction.NEXT, 2, DateTimeCursor.parse("3000"));
    verify(commentQueryService, never()).findByArticleId(any(), any());
  }

  @Test
  public void should_get_comments_previous_page_with_cursor() throws Exception {
    when(commentQueryService.findByArticleIdWithCursor(eq(article.getId()), eq(user), any()))
        .thenReturn(new CursorPager<>(asList(first), Direction.PREV, true));

    given()
        .header("Authorization", "Token " + token)
        .param("last", 1)
        .param("before", "1000")
        .when()
        .get("/articles/{slug}/comments", article.getSlug())
        .then()
        .statusCode(200)
        .body("comments", hasSize(1))
        .body("startCursor", equalTo("2000"))
        .body("endCursor", equalTo("2000"))
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(true));

    ArgumentCaptor<CursorPageParameter<DateTime>> captor =
        ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService)
        .findByArticleIdWithCursor(eq(article.getId()), eq(user), captor.capture());
    assertPage(captor.getValue(), Direction.PREV, 1, DateTimeCursor.parse("1000"));
  }

  @Test
  public void should_get_empty_comment_page_with_null_cursors() throws Exception {
    when(commentQueryService.findByArticleIdWithCursor(eq(article.getId()), eq(null), any()))
        .thenReturn(new CursorPager<>(new ArrayList<>(), Direction.NEXT, false));

    given()
        .param("first", 5)
        .when()
        .get("/articles/{slug}/comments", article.getSlug())
        .then()
        .statusCode(200)
        .body("comments", hasSize(0))
        .body("startCursor", nullValue())
        .body("endCursor", nullValue())
        .body("hasNext", equalTo(false))
        .body("hasPrevious", equalTo(false));
  }

  @Test
  public void should_get_404_for_cursor_comments_of_unknown_article() throws Exception {
    given()
        .param("first", 5)
        .when()
        .get("/articles/{slug}/comments", "not-exists")
        .then()
        .statusCode(404);
  }

  @Test
  public void should_keep_full_list_when_no_cursor_params() throws Exception {
    when(commentQueryService.findByArticleId(eq(article.getId()), eq(null)))
        .thenReturn(asList(first, second));

    RestAssuredMockMvc.when()
        .get("/articles/{slug}/comments", article.getSlug())
        .then()
        .statusCode(200)
        .body("comments", hasSize(2));

    verify(commentQueryService, never()).findByArticleIdWithCursor(any(), any(), any());
  }

  private static void assertPage(
      CursorPageParameter<DateTime> page, Direction direction, int limit, DateTime cursor) {
    assertEquals(direction, page.getDirection());
    assertEquals(limit, page.getLimit());
    assertEquals(cursor, page.getCursor());
  }

  private CommentData commentAt(String id, DateTime createdAt) {
    return new CommentData(
        id,
        "body " + id,
        article.getId(),
        createdAt,
        createdAt,
        new ProfileData(user.getId(), user.getUsername(), user.getBio(), user.getImage(), false));
  }
}
