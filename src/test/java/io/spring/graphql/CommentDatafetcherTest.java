package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import graphql.execution.DataFetcherResult;
import io.spring.TestHelper;
import io.spring.application.CommentQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.ArticleData;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.user.User;
import io.spring.graphql.types.Article;
import io.spring.graphql.types.Comment;
import io.spring.graphql.types.CommentsConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class CommentDatafetcherTest extends GraphQLTestBase {
  private CommentQueryService commentQueryService;
  private CommentDatafetcher commentDatafetcher;
  private User user;
  private ArticleData articleData;
  private DgsDataFetchingEnvironment dfe;

  @BeforeEach
  public void setUp() {
    commentQueryService = mock(CommentQueryService.class);
    commentDatafetcher = new CommentDatafetcher(commentQueryService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    articleData = TestHelper.articleDataFixture("1", user);
    Map<String, ArticleData> map = new HashMap<>();
    map.put(articleData.getSlug(), articleData);
    dfe = mock(DgsDataFetchingEnvironment.class);
    when(dfe.getLocalContext()).thenReturn(map);
    when(dfe.getSource()).thenReturn(Article.newBuilder().slug(articleData.getSlug()).build());
  }

  private CommentData comment(String id, long millis) {
    DateTime time = new DateTime(millis, DateTimeZone.UTC);
    return new CommentData(
        id,
        "body " + id,
        articleData.getId(),
        time,
        time,
        new ProfileData(user.getId(), user.getUsername(), "", "", false));
  }

  @Test
  public void should_build_comment_from_local_context() {
    CommentData commentData = comment("c1", 1000L);
    DgsDataFetchingEnvironment env = mock(DgsDataFetchingEnvironment.class);
    when(env.getLocalContext()).thenReturn(commentData);

    DataFetcherResult<Comment> result = commentDatafetcher.getComment(env);

    Assertions.assertEquals("c1", result.getData().getId());
    Assertions.assertEquals("body c1", result.getData().getBody());
    Assertions.assertEquals("1970-01-01T00:00:01.000Z", result.getData().getCreatedAt());
    Assertions.assertEquals(commentData, ((Map<?, ?>) result.getLocalContext()).get("c1"));
  }

  @Test
  public void should_reject_article_comments_without_first_or_last() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> commentDatafetcher.articleComments(null, null, null, null, dfe));
  }

  @Test
  public void should_fetch_next_page_of_article_comments() {
    login(user);
    CommentData c1 = comment("c1", 2000L);
    CommentData c2 = comment("c2", 1000L);
    when(commentQueryService.findByArticleIdWithCursor(eq(articleData.getId()), eq(user), any()))
        .thenReturn(
            new CursorPager<>(new ArrayList<>(Arrays.asList(c1, c2)), Direction.NEXT, true));

    DataFetcherResult<CommentsConnection> result =
        commentDatafetcher.articleComments(2, "5000", null, null, dfe);

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService)
        .findByArticleIdWithCursor(eq(articleData.getId()), eq(user), captor.capture());
    Assertions.assertEquals(Direction.NEXT, captor.getValue().getDirection());
    Assertions.assertEquals(2, captor.getValue().getLimit());
    Assertions.assertEquals(5000L, ((DateTime) captor.getValue().getCursor()).getMillis());

    CommentsConnection connection = result.getData();
    Assertions.assertEquals(2, connection.getEdges().size());
    Assertions.assertEquals("2000", connection.getEdges().get(0).getCursor());
    Assertions.assertEquals("c1", connection.getEdges().get(0).getNode().getId());
    Assertions.assertTrue(connection.getPageInfo().isHasNextPage());
    Assertions.assertFalse(connection.getPageInfo().isHasPreviousPage());
    Assertions.assertEquals("2000", connection.getPageInfo().getStartCursor().getValue());
    Assertions.assertEquals("1000", connection.getPageInfo().getEndCursor().getValue());
    Map<?, ?> localContext = (Map<?, ?>) result.getLocalContext();
    Assertions.assertEquals(c2, localContext.get("c2"));
  }

  @Test
  public void should_fetch_previous_page_of_article_comments_for_anonymous_user() {
    anonymous();
    when(commentQueryService.findByArticleIdWithCursor(eq(articleData.getId()), isNull(), any()))
        .thenReturn(new CursorPager<>(new ArrayList<>(), Direction.PREV, false));

    DataFetcherResult<CommentsConnection> result =
        commentDatafetcher.articleComments(null, null, 3, null, dfe);

    ArgumentCaptor<CursorPageParameter> captor = ArgumentCaptor.forClass(CursorPageParameter.class);
    verify(commentQueryService)
        .findByArticleIdWithCursor(eq(articleData.getId()), isNull(), captor.capture());
    Assertions.assertEquals(Direction.PREV, captor.getValue().getDirection());
    Assertions.assertEquals(3, captor.getValue().getLimit());
    Assertions.assertNull(captor.getValue().getCursor());
    Assertions.assertTrue(result.getData().getEdges().isEmpty());
    Assertions.assertNull(result.getData().getPageInfo().getStartCursor());
    Assertions.assertNull(result.getData().getPageInfo().getEndCursor());
  }
}
