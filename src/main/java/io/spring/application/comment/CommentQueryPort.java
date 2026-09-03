package io.spring.application.comment;

import io.spring.application.CursorPageParameter;
import io.spring.application.data.CommentData;
import java.util.List;
import org.joda.time.DateTime;

/**
 * Read side of the Comment domain as seen by the monolith. Every implementation returns {@link
 * CommentData} with the author profile filled in and {@code following == false}; {@code
 * CommentQueryService} adds the relationship flag. Implemented by the MyBatis read service (the
 * monolith join in {@code CommentReadService.xml}), by the remote adapter (comment-service rows
 * composed with local profiles) and by the routing port that picks one per call according to {@code
 * extraction.comment.*}.
 */
public interface CommentQueryPort {
  CommentData findById(String id);

  /** Ordered {@code created_at DESC}. */
  List<CommentData> findByArticleId(String articleId);

  /**
   * Up to {@code page.getLimit() + 1} rows using the {@code created_at <} (next) / {@code >} (prev)
   * cursor semantics; the caller trims the probe row and computes {@code hasNext}.
   */
  List<CommentData> findByArticleIdWithCursor(String articleId, CursorPageParameter<DateTime> page);
}
