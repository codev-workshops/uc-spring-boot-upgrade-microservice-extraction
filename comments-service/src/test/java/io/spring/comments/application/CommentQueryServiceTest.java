package io.spring.comments.application;

import io.spring.comments.application.data.CommentData;
import io.spring.comments.core.comment.Comment;
import io.spring.comments.core.comment.CommentRepository;
import io.spring.comments.infrastructure.DbTestBase;
import io.spring.comments.infrastructure.repository.MyBatisCommentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({MyBatisCommentRepository.class, CommentQueryService.class})
public class CommentQueryServiceTest extends DbTestBase {
  @Autowired private CommentRepository commentRepository;

  @Autowired private CommentQueryService commentQueryService;

  @Test
  public void should_read_comment_success() {
    Comment comment = new Comment("content", "user-id", "article-id");
    commentRepository.save(comment);

    Optional<CommentData> optional = commentQueryService.findById(comment.getId());
    Assertions.assertTrue(optional.isPresent());
    CommentData commentData = optional.get();
    Assertions.assertEquals("content", commentData.getBody());
    Assertions.assertEquals("user-id", commentData.getUserId());
    Assertions.assertEquals("article-id", commentData.getArticleId());
    Assertions.assertNotNull(commentData.getCreatedAt());
  }

  @Test
  public void should_read_comments_of_article() {
    String articleId = UUID.randomUUID().toString();
    commentRepository.save(new Comment("content1", "user1", articleId));
    commentRepository.save(new Comment("content2", "user2", articleId));
    commentRepository.save(new Comment("other", "user2", "another-article"));

    List<CommentData> comments = commentQueryService.findByArticleId(articleId);
    Assertions.assertEquals(2, comments.size());
  }

  @Test
  public void should_read_comments_with_cursor() throws InterruptedException {
    String articleId = UUID.randomUUID().toString();
    for (int i = 0; i < 3; i++) {
      commentRepository.save(new Comment("content" + i, "user", articleId));
      Thread.sleep(5);
    }

    List<CommentData> firstPage =
        commentQueryService.findByArticleIdWithCursor(
            articleId, new CursorPageParameter(null, 2, Direction.NEXT));
    Assertions.assertEquals(3, firstPage.size());
    Assertions.assertEquals("content2", firstPage.get(0).getBody());

    DateTime cursor = firstPage.get(1).getCreatedAt();
    List<CommentData> nextPage =
        commentQueryService.findByArticleIdWithCursor(
            articleId, new CursorPageParameter(cursor, 2, Direction.NEXT));
    Assertions.assertEquals(1, nextPage.size());
    Assertions.assertEquals("content0", nextPage.get(0).getBody());
  }
}
