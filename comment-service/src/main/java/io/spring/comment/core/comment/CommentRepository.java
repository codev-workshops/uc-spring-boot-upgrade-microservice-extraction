package io.spring.comment.core.comment;

import java.util.Optional;

public interface CommentRepository {
  /** Idempotent: inserting an id that already exists is a no-op. */
  void save(Comment comment);

  Optional<Comment> findById(String articleId, String id);

  /** Idempotent: removing an absent comment is a no-op. */
  void remove(Comment comment);
}
