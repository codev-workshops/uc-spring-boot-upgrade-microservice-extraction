package io.spring.infrastructure.extraction.comment;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
import org.springframework.stereotype.Component;

/** Writes to comment-service; failures propagate as {@link CommentServiceException}. */
@Component
public class RemoteCommentCommand implements CommentCommandPort {
  private final CommentServiceClient client;

  public RemoteCommentCommand(CommentServiceClient client) {
    this.client = client;
  }

  @Override
  public void create(Comment comment) {
    client.create(comment);
  }

  @Override
  public void delete(String articleId, String commentId) {
    client.delete(articleId, commentId);
  }
}
