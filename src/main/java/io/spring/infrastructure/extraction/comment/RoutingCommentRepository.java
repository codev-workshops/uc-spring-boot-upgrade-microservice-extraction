package io.spring.infrastructure.extraction.comment;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link CommentRepository} {@code CommentsApi} and {@code CommentMutation} see. Writes go
 * through the routing {@link CommentCommandPort}; {@link #findById} (used for the 404 and the
 * article-author-or-comment-author check, both of which stay in the monolith and run before any
 * remote write) reads the monolith table while it is authoritative and comment-service once {@code
 * write=extracted}.
 */
@Primary
@Repository
public class RoutingCommentRepository implements CommentRepository {
  private final MyBatisCommentRepository monolith;
  private final CommentCommandPort commands;
  private final CommentServiceClient client;
  private final ExtractionProperties properties;

  public RoutingCommentRepository(
      MyBatisCommentRepository monolith,
      CommentCommandPort commands,
      CommentServiceClient client,
      ExtractionProperties properties) {
    this.monolith = monolith;
    this.commands = commands;
    this.client = client;
    this.properties = properties;
  }

  @Override
  public void save(Comment comment) {
    commands.create(comment);
  }

  @Override
  public Optional<Comment> findById(String articleId, String id) {
    if (properties.getComment().monolithAuthoritative()) {
      return monolith.findById(articleId, id);
    }
    return client
        .findById(id)
        .filter(row -> articleId.equals(row.getArticleId()))
        .map(
            row ->
                new Comment(
                    row.getId(),
                    row.getBody(),
                    row.getUserId(),
                    row.getArticleId(),
                    RemoteCommentQueryAdapter.parse(row.getCreatedAt())));
  }

  @Override
  public void remove(Comment comment) {
    commands.delete(comment.getArticleId(), comment.getId());
  }
}
