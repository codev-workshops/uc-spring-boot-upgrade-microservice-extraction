package io.spring.article.infrastructure.mybatis.readservice;

import io.spring.article.application.CursorPageParameter;
import io.spring.article.application.Page;
import io.spring.article.application.data.ArticleData;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * The monolith's ArticleReadService.xml statements minus every users / article_favorites join: the
 * author filter is a resolved user id and the favorited filter arrives as an id allow-list.
 */
@Mapper
public interface ArticleReadService {
  ArticleData findById(@Param("id") String id);

  ArticleData findBySlug(@Param("slug") String slug);

  /** Rows for the given ids, created_at DESC (findArticles). */
  List<ArticleData> findArticles(@Param("articleIds") List<String> articleIds);

  /** DISTINCT ids, created_at DESC, LIMIT offset,limit (queryArticles / queryArticlesByIds). */
  List<String> queryArticleIds(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds,
      @Param("page") Page page);

  /** count(DISTINCT A.id) with the same filters (countArticle / countArticleByIds). */
  int countArticles(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds);

  /** Up to limit+1 ids with the cursor predicates (findArticlesWithCursor[ByIds]). */
  List<String> findArticleIdsWithCursor(
      @Param("tag") String tag,
      @Param("authorId") String authorId,
      @Param("articleIds") List<String> articleIds,
      @Param("page") CursorPageParameter page);

  /** Verbatim findArticlesOfAuthors: no ORDER BY, LIMIT applied to the joined rows. */
  List<ArticleData> findArticlesOfAuthors(
      @Param("authors") List<String> authors, @Param("page") Page page);

  int countFeedSize(@Param("authors") List<String> authors);

  List<ArticleData> findArticlesOfAuthorsWithCursor(
      @Param("authors") List<String> authors, @Param("page") CursorPageParameter page);
}
