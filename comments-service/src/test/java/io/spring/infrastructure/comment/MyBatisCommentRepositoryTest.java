package io.spring.infrastructure.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MyBatisCommentRepository.class)
public class MyBatisCommentRepositoryTest extends DbTestBase {
  @Autowired private CommentRepository commentRepository;

  @Test
  public void shouldCreateAndFetchComment() {
    Comment comment = new Comment("content", "user-1", "article-1");
    commentRepository.save(comment);

    Optional<Comment> result = commentRepository.findById("article-1", comment.getId());

    assertTrue(result.isPresent());
    assertEquals(comment, result.get());
  }
}
