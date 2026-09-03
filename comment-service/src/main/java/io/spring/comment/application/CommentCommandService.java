package io.spring.comment.application;

import io.spring.comment.application.data.CommentData;
import io.spring.comment.core.comment.Comment;
import io.spring.comment.core.comment.CommentRepository;
import java.util.Optional;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

@Service
public class CommentCommandService {
  private final CommentRepository commentRepository;

  public CommentCommandService(CommentRepository commentRepository) {
    this.commentRepository = commentRepository;
  }

  /** Outcome of an idempotent create: the stored row plus whether this call inserted it. */
  public static final class CreateResult {
    private final CommentData comment;
    private final boolean created;

    public CreateResult(CommentData comment, boolean created) {
      this.comment = comment;
      this.created = created;
    }

    public CommentData getComment() {
      return comment;
    }

    public boolean isCreated() {
      return created;
    }
  }

  /**
   * Idempotent: if a comment with the caller-supplied id already exists for the article, the stored
   * row is returned unchanged and nothing is written.
   */
  public CreateResult create(
      String articleId, String id, String body, String userId, DateTime createdAt) {
    if (id != null && !id.isEmpty()) {
      Optional<Comment> existing = commentRepository.findById(articleId, id);
      if (existing.isPresent()) {
        return new CreateResult(toData(existing.get()), false);
      }
    }
    Comment comment = new Comment(id, body, userId, articleId, createdAt);
    commentRepository.save(comment);
    Comment stored = commentRepository.findById(articleId, comment.getId()).orElse(comment);
    return new CreateResult(toData(stored), true);
  }

  /**
   * Idempotent: deleting a comment that does not exist (or belongs to another article) is a no-op.
   */
  public void delete(String articleId, String id) {
    commentRepository.findById(articleId, id).ifPresent(commentRepository::remove);
  }

  private static CommentData toData(Comment comment) {
    return new CommentData(
        comment.getId(),
        comment.getBody(),
        comment.getArticleId(),
        comment.getUserId(),
        comment.getCreatedAt(),
        comment.getCreatedAt());
  }
}
