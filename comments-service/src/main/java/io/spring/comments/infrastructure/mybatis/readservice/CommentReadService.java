package io.spring.comments.infrastructure.mybatis.readservice;

import io.spring.comments.application.CursorPageParameter;
import io.spring.comments.application.data.CommentData;
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
