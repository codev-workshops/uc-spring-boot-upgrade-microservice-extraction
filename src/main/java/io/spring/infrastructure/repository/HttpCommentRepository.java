package io.spring.infrastructure.repository;

import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.infrastructure.comments.CommentServiceClient;
import io.spring.infrastructure.comments.RemoteComment;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class HttpCommentRepository implements CommentRepository {
  private final CommentServiceClient commentServiceClient;

  public HttpCommentRepository(CommentServiceClient commentServiceClient) {
    this.commentServiceClient = commentServiceClient;
  }

  @Override
  public Comment save(Comment comment) {
    return toComment(
        commentServiceClient.create(
            comment.getArticleId(), comment.getId(), comment.getUserId(), comment.getBody()));
  }

  @Override
  public Optional<Comment> findById(String articleId, String id) {
    return commentServiceClient.findByArticleIdAndId(articleId, id).map(this::toComment);
  }

  @Override
  public void remove(Comment comment) {
    commentServiceClient.delete(comment.getArticleId(), comment.getId());
  }

  private Comment toComment(RemoteComment remote) {
    return new Comment(
        remote.getId(),
        remote.getBody(),
        remote.getUserId(),
        remote.getArticleId(),
        remote.createdAtTime());
  }
}
