package io.spring.comment.application;

import io.spring.comment.application.data.CommentData;
import io.spring.comment.infrastructure.mybatis.readservice.CommentReadService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class CommentQueryService {
  private final CommentReadService commentReadService;

  public CommentQueryService(CommentReadService commentReadService) {
    this.commentReadService = commentReadService;
  }

  public Optional<CommentData> findById(String id) {
    return Optional.ofNullable(commentReadService.findById(id));
  }

  /** All comments of the article, created_at DESC. */
  public List<CommentData> findByArticleId(String articleId) {
    return commentReadService.findByArticleId(articleId);
  }

  /**
   * Up to limit+1 rows using the monolith's created_at &lt;/&gt; cursor predicates, DESC for NEXT
   * and ASC for PREV; the caller trims the probe row and computes hasNext/hasPrevious.
   */
  public List<CommentData> findByArticleIdWithCursor(String articleId, CursorPageParameter page) {
    return commentReadService.findByArticleIdWithCursor(articleId, page);
  }
}
