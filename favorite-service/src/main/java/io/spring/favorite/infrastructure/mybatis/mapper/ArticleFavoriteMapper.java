package io.spring.favorite.infrastructure.mybatis.mapper;

import io.spring.favorite.core.favorite.ArticleFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleFavoriteMapper {
  ArticleFavorite find(@Param("articleId") String articleId, @Param("userId") String userId);

  void insert(@Param("favorite") ArticleFavorite favorite);

  void delete(@Param("favorite") ArticleFavorite favorite);
}
