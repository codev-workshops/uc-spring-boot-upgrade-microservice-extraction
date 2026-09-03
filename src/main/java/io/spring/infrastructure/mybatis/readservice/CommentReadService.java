package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.CursorPageParameter;
import io.spring.application.comment.CommentQueryPort;
import io.spring.application.data.CommentData;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.joda.time.DateTime;

/** Monolith-table implementation of {@link CommentQueryPort} (the MyBatis adapter). */
@Mapper
public interface CommentReadService extends CommentQueryPort {
  @Override
  CommentData findById(@Param("id") String id);

  @Override
  List<CommentData> findByArticleId(@Param("articleId") String articleId);

  @Override
  List<CommentData> findByArticleIdWithCursor(
      @Param("articleId") String articleId, @Param("page") CursorPageParameter<DateTime> page);
}
