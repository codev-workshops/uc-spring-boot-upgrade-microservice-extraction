package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.data.ArticleRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Article-domain-only reads over the monolith {@code articles} / {@code article_tags} / {@code
 * tags} tables (no users, no favorites): the local implementation of {@code ArticleQueryPort}, and
 * the SQL article-service mirrors. Filters are by id, never by username.
 */
@Mapper
public interface ArticleRowReadService {
  ArticleRow findById(@Param("id") String id);

  ArticleRow findBySlug(@Param("slug") String slug);

  List<ArticleRow> findArticles(@Param("articleIds") List<String> articleIds);

  List<String> queryArticleIds(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds,
      @Param("page") Page page);

  int countArticleIds(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds);

  List<String> queryArticleIdsWithCursor(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds,
      @Param("page") CursorPageParameter page);

  List<ArticleRow> findArticlesOfAuthors(
      @Param("authorIds") List<String> authorIds, @Param("page") Page page);

  int countArticlesOfAuthors(@Param("authorIds") List<String> authorIds);

  List<ArticleRow> findArticlesOfAuthorsWithCursor(
      @Param("authorIds") List<String> authorIds, @Param("page") CursorPageParameter page);
}
