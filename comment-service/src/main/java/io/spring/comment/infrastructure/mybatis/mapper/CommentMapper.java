package io.spring.comment.infrastructure.mybatis.mapper;

import io.spring.comment.core.comment.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentMapper {
  void insert(@Param("comment") Comment comment);

  void delete(@Param("id") String id);

  Comment findById(@Param("articleId") String articleId, @Param("id") String id);
}
