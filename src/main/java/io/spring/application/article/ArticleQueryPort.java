package io.spring.application.article;

import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.data.ArticleRow;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * Read side of the Article domain as seen by the monolith: article rows and id pages only, every
 * filter by id. Implemented by the MyBatis adapter (monolith {@code articles} table), by the remote
 * adapter (article-service) and by the routing port that picks one of them per call according to
 * {@code extraction.article.*}. Usernames ({@code author=}, {@code favorited=}) and profiles are
 * resolved by {@code ArticleQueryService} before/after calling this port.
 */
public interface ArticleQueryPort {
  Optional<ArticleRow> findById(String id);

  Optional<ArticleRow> findBySlug(String slug);

  /** Rows of the given ids ordered {@code created_at DESC}; an empty list yields an empty list. */
  List<ArticleRow> findArticles(List<String> articleIds);

  /**
   * Distinct ids matching the filters, {@code created_at DESC}, offset paged, plus the total count.
   * {@code articleIds} is an optional allow-list ({@code null} = no restriction).
   */
  ArticleIdPage queryArticleIds(String tag, String authorId, List<String> articleIds, Page page);

  /** Up to {@code limit + 1} ids with the monolith's cursor semantics. */
  List<String> queryArticleIdsWithCursor(
      String tag, String authorId, List<String> articleIds, CursorPageParameter<DateTime> page);

  /** Rows of the given authors, offset paged, plus the total count. */
  ArticleRowPage findArticlesOfAuthors(List<String> authorIds, Page page);

  /** Up to {@code limit + 1} rows of the given authors with the monolith's cursor semantics. */
  List<ArticleRow> findArticlesOfAuthorsWithCursor(
      List<String> authorIds, CursorPageParameter<DateTime> page);

  /**
   * Whether {@code ArticleQueryService} must compose {@code ArticleData} from this port instead of
   * the SQL joins in {@code ArticleReadService.xml}.
   */
  default boolean ownsArticleReads() {
    return false;
  }
}
