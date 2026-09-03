package io.spring.application.tag;

import io.spring.application.data.ArticleTagList;
import java.util.List;

/**
 * Read side of the Tag domain as seen by the monolith. Implemented by the MyBatis read service
 * (monolith {@code tags}/{@code article_tags} tables), by the remote adapter (article-service) and
 * by the routing port that picks one of them per call according to {@code extraction.tag.*}.
 */
public interface TagQueryPort {
  /** Same rows and order as {@code select name from tags}. */
  List<String> allTags();

  /**
   * Must return exactly one entry per requested id (empty {@code tagList} for an article without
   * tags), tags in {@code article_tags} insertion order.
   */
  List<ArticleTagList> tagsByArticleIds(List<String> articleIds);

  /** Ids of every article carrying the tag; unknown tag yields an empty list. */
  List<String> articleIdsByTag(String tagName);

  /**
   * Whether {@code ArticleData.tagList} and the {@code tag=} article filter must be resolved
   * through this port instead of the SQL joins in {@code ArticleReadService.xml}.
   */
  default boolean ownsTagReads() {
    return false;
  }
}
