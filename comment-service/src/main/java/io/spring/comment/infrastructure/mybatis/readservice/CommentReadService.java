package io.spring.comment.infrastructure.mybatis.readservice;

import io.spring.comment.application.CursorPageParameter;
import io.spring.comment.application.data.CommentData;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentReadService {
  CommentData findById(@Param("id") String id);

  List<CommentData> findByArticleId(@Param("articleId") String articleId);

  List<CommentData> findByArticleIdWithCursor(
      @Param("articleId") String articleId, @Param("page") CursorPageParameter page);
}
