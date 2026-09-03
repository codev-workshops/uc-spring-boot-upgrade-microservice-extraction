package io.spring.comment.infrastructure.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.comment.application.CommentCommandService;
import io.spring.comment.application.CommentQueryService;
import io.spring.comment.application.CursorPageParameter;
import io.spring.comment.application.CursorPageParameter.Direction;
import io.spring.comment.application.data.CommentData;
import io.spring.comment.core.comment.Comment;
import io.spring.comment.infrastructure.DbTestBase;
import io.spring.comment.infrastructure.repository.MyBatisCommentRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({MyBatisCommentRepository.class, CommentQueryService.class, CommentCommandService.class})
public class CommentReadServiceTest extends DbTestBase {
  @Autowired private MyBatisCommentRepository repository;
  @Autowired private CommentQueryService queryService;
  @Autowired private CommentCommandService commandService;

  private static final DateTime BASE = new DateTime(1700000000000L, DateTimeZone.UTC);

  /** c-1 .. c-5 on article-1 at BASE + 1s .. + 5s; c-9 on article-2. */
  @BeforeEach
  public void setUp() {
    for (int i = 1; i <= 5; i++) {
      repository.save(new Comment("c-" + i, "body " + i, "user-" + i, "article-1", at(i)));
    }
    repository.save(new Comment("c-9", "other", "user-1", "article-2", at(9)));
  }

  private static DateTime at(int seconds) {
    return BASE.plusSeconds(seconds);
  }

  private static List<String> ids(List<CommentData> comments) {
    return comments.stream().map(CommentData::getId).collect(Collectors.toList());
  }

  @Test
  public void list_is_ordered_created_at_desc_and_scoped_to_article() {
    List<CommentData> comments = queryService.findByArticleId("article-1");
    assertEquals(List.of("c-5", "c-4", "c-3", "c-2", "c-1"), ids(comments));
    assertTrue(queryService.findByArticleId("article-none").isEmpty());
  }

  @Test
  public void row_has_raw_fields_and_updated_at_equals_created_at() {
    CommentData data = queryService.findById("c-3").get();
    assertEquals("c-3", data.getId());
    assertEquals("body 3", data.getBody());
    assertEquals("article-1", data.getArticleId());
    assertEquals("user-3", data.getUserId());
    assertEquals(at(3).getMillis(), data.getCreatedAt().getMillis());
    assertEquals(data.getCreatedAt().getMillis(), data.getUpdatedAt().getMillis());
    assertFalse(queryService.findById("nope").isPresent());
  }

  @Test
  public void cursor_next_without_cursor_returns_limit_plus_one_newest_first() {
    List<CommentData> page =
        queryService.findByArticleIdWithCursor(
            "article-1", new CursorPageParameter(null, 2, Direction.NEXT));
    assertEquals(List.of("c-5", "c-4", "c-3"), ids(page));
  }

  @Test
  public void cursor_next_is_strictly_older_than_cursor() {
    List<CommentData> page =
        queryService.findByArticleIdWithCursor(
            "article-1", new CursorPageParameter(at(3), 2, Direction.NEXT));
    assertEquals(List.of("c-2", "c-1"), ids(page));
  }

  @Test
  public void cursor_prev_is_strictly_newer_than_cursor_ascending() {
    List<CommentData> page =
        queryService.findByArticleIdWithCursor(
            "article-1", new CursorPageParameter(at(3), 1, Direction.PREV));
    assertEquals(List.of("c-4", "c-5"), ids(page));
  }

  @Test
  public void cursor_at_boundaries_returns_empty() {
    assertTrue(
        queryService
            .findByArticleIdWithCursor(
                "article-1", new CursorPageParameter(at(1), 20, Direction.NEXT))
            .isEmpty());
    assertTrue(
        queryService
            .findByArticleIdWithCursor(
                "article-1", new CursorPageParameter(at(5), 20, Direction.PREV))
            .isEmpty());
  }

  @Test
  public void cursor_limit_is_clamped_like_the_monolith() {
    assertEquals(20, new CursorPageParameter(null, 0, Direction.NEXT).getLimit());
    assertEquals(1000, new CursorPageParameter(null, 5000, Direction.NEXT).getLimit());
    assertEquals(6, new CursorPageParameter(null, 5, Direction.NEXT).getQueryLimit());
  }

  @Test
  public void create_is_idempotent_on_id_and_delete_is_idempotent() {
    CommentCommandService.CreateResult first =
        commandService.create("article-1", "c-7", "new", "user-7", at(7));
    assertTrue(first.isCreated());
    assertEquals(at(7).getMillis(), first.getComment().getCreatedAt().getMillis());
    CommentCommandService.CreateResult again =
        commandService.create("article-1", "c-7", "changed", "user-8", at(8));
    assertFalse(again.isCreated());
    assertEquals("new", again.getComment().getBody());
    assertEquals("user-7", again.getComment().getUserId());
    assertEquals(6, queryService.findByArticleId("article-1").size());

    commandService.delete("article-1", "c-7");
    commandService.delete("article-1", "c-7");
    commandService.delete("article-2", "c-1");
    assertEquals(5, queryService.findByArticleId("article-1").size());
    assertTrue(queryService.findById("c-1").isPresent());
  }
}
