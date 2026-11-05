package io.spring.comments.application;

import io.spring.comments.application.data.CommentData;
import io.spring.comments.infrastructure.mybatis.readservice.CommentReadService;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class CommentQueryService {
  private CommentReadService commentReadService;

  public Optional<CommentData> findById(String id) {
    return Optional.ofNullable(commentReadService.findById(id));
  }

  public List<CommentData> findByArticleId(String articleId) {
    return commentReadService.findByArticleId(articleId);
  }

  /**
   * Returns up to {@code page.getQueryLimit()} comments (one more than requested) so callers can
   * tell whether another page exists.
   */
  public List<CommentData> findByArticleIdWithCursor(String articleId, CursorPageParameter page) {
    return commentReadService.findByArticleIdWithCursor(articleId, page);
  }
}
