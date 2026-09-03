package io.spring.article.infrastructure.mybatis.mapper;

import io.spring.article.core.tag.Tag;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TagMapper {
  Tag findByName(@Param("name") String name);

  void insert(@Param("tag") Tag tag);

  int countRelation(@Param("articleId") String articleId, @Param("tagId") String tagId);

  void insertRelation(@Param("articleId") String articleId, @Param("tagId") String tagId);

  List<String> findTagNames(@Param("articleId") String articleId);
}
