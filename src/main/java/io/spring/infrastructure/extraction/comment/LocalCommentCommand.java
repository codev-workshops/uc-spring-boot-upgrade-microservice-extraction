package io.spring.infrastructure.extraction.comment;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import org.springframework.stereotype.Component;

/** Writes to the monolith {@code comments} table. */
@Component
public class LocalCommentCommand implements CommentCommandPort {
  private final MyBatisCommentRepository repository;

  public LocalCommentCommand(MyBatisCommentRepository repository) {
    this.repository = repository;
  }

  @Override
  public void create(Comment comment) {
    repository.save(comment);
  }

  @Override
  public void delete(String articleId, String commentId) {
    repository.findById(articleId, commentId).ifPresent(repository::remove);
  }
}
