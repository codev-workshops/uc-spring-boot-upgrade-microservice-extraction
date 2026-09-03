package io.spring.application.comment;

import io.spring.core.comment.Comment;

/**
 * Write side of the Comment domain. The monolith generates the comment id and {@code createdAt}
 * (see {@link Comment#Comment(String, String, String)}) so every store receives identical rows.
 * Both operations are idempotent.
 */
public interface CommentCommandPort {
  void create(Comment comment);

  void delete(String articleId, String commentId);
}
