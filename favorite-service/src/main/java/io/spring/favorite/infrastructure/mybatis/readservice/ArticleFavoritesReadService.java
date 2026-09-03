package io.spring.favorite.infrastructure.mybatis.readservice;

import io.spring.favorite.application.data.ArticleFavoriteCount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleFavoritesReadService {
  List<ArticleFavoriteCount> articlesFavoriteCount(@Param("ids") List<String> ids);

  List<String> userFavorites(@Param("ids") List<String> ids, @Param("userId") String userId);

  List<String> articleIdsFavoritedBy(@Param("userId") String userId);
}
