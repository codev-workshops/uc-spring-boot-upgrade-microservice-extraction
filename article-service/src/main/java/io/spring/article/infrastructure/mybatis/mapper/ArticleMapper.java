package io.spring.article.infrastructure.mybatis.mapper;

import io.spring.article.core.article.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleMapper {
  void insert(@Param("article") Article article);

  Article findById(@Param("id") String id);

  Article findBySlug(@Param("slug") String slug);

  void update(
      @Param("id") String id,
      @Param("title") String title,
      @Param("slug") String slug,
      @Param("description") String description,
      @Param("body") String body);

  void delete(@Param("id") String id);
}
