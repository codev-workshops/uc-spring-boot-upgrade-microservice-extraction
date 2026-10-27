package io.spring.articles.infrastructure.mybatis.readservice;

import io.spring.articles.application.Page;
import io.spring.articles.application.data.ArticleData;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleReadService {
  ArticleData findById(@Param("id") String id);

  ArticleData findBySlug(@Param("slug") String slug);

  List<String> queryArticles(
      @Param("tag") String tag,
      @Param("authorIds") List<String> authorIds,
      @Param("articleIds") List<String> articleIds,
      @Param("page") Page page);

  int countArticle(
      @Param("tag") String tag,
      @Param("authorIds") List<String> authorIds,
      @Param("articleIds") List<String> articleIds);

  List<ArticleData> findArticles(@Param("articleIds") List<String> articleIds);
}
