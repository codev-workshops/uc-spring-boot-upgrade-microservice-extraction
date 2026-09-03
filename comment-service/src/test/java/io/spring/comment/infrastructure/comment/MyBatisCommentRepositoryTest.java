package io.spring.comment.infrastructure.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.comment.core.comment.Comment;
import io.spring.comment.core.comment.CommentRepository;
import io.spring.comment.infrastructure.DbTestBase;
import io.spring.comment.infrastructure.mybatis.readservice.CommentReadService;
import io.spring.comment.infrastructure.repository.MyBatisCommentRepository;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MyBatisCommentRepository.class)
public class MyBatisCommentRepositoryTest extends DbTestBase {
  @Autowired private CommentRepository commentRepository;
  @Autowired private CommentReadService readService;

  private static final DateTime T1 = new DateTime(1700000000123L, DateTimeZone.UTC);

  @Test
  public void should_save_and_fetch_comment_with_millis_precision() {
    Comment comment = new Comment("c-1", "hello", "user-1", "article-1", T1);
    commentRepository.save(comment);
    Optional<Comment> found = commentRepository.findById("article-1", "c-1");
    assertTrue(found.isPresent());
    assertEquals("hello", found.get().getBody());
    assertEquals("user-1", found.get().getUserId());
    assertEquals("article-1", found.get().getArticleId());
    assertEquals(T1.getMillis(), found.get().getCreatedAt().getMillis());
  }

  @Test
  public void should_generate_id_and_created_at_when_absent() {
    Comment comment = new Comment(null, "hello", "user-1", "article-1", null);
    commentRepository.save(comment);
    assertTrue(comment.getId().matches("[0-9a-f-]{36}"));
    assertTrue(commentRepository.findById("article-1", comment.getId()).isPresent());
  }

  @Test
  public void should_ignore_double_insert_and_keep_first_row() {
    commentRepository.save(new Comment("c-1", "first", "user-1", "article-1", T1));
    commentRepository.save(new Comment("c-1", "second", "user-2", "article-1", T1.plusDays(1)));
    assertEquals(1, readService.findByArticleId("article-1").size());
    assertEquals("first", commentRepository.findById("article-1", "c-1").get().getBody());
  }

  @Test
  public void should_not_find_comment_under_another_article() {
    commentRepository.save(new Comment("c-1", "hello", "user-1", "article-1", T1));
    assertFalse(commentRepository.findById("article-2", "c-1").isPresent());
  }

  @Test
  public void should_remove_comment_and_treat_absent_as_noop() {
    Comment comment = new Comment("c-1", "hello", "user-1", "article-1", T1);
    commentRepository.save(comment);
    commentRepository.remove(comment);
    assertFalse(commentRepository.findById("article-1", "c-1").isPresent());
    commentRepository.remove(comment);
    assertFalse(commentRepository.findById("article-1", "c-1").isPresent());
  }
}
