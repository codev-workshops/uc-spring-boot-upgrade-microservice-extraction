package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.core.user.User;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Monolith-table implementation of {@link FavoriteQueryPort} (the MyBatis adapter). */
@Mapper
public interface ArticleFavoritesReadService extends FavoriteQueryPort {
  @Override
  boolean isUserFavorite(@Param("userId") String userId, @Param("articleId") String articleId);

  @Override
  int articleFavoriteCount(@Param("articleId") String articleId);

  @Override
  List<ArticleFavoriteCount> articlesFavoriteCount(@Param("ids") List<String> ids);

  @Override
  Set<String> userFavorites(@Param("ids") List<String> ids, @Param("currentUser") User currentUser);

  @Override
  List<String> articleIdsFavoritedBy(@Param("userId") String userId);
}
