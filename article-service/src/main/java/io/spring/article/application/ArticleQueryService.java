package io.spring.article.application;

import io.spring.article.application.data.ArticleData;
import io.spring.article.application.data.ArticleIdsData;
import io.spring.article.application.data.ArticleListData;
import io.spring.article.infrastructure.mybatis.readservice.ArticleReadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read side of the Article domain: raw rows only (phase-4-article.md §2.1). */
@Service
public class ArticleQueryService {
  private final ArticleReadService articleReadService;

  public ArticleQueryService(ArticleReadService articleReadService) {
    this.articleReadService = articleReadService;
  }

  public Optional<ArticleData> findById(String id) {
    return Optional.ofNullable(articleReadService.findById(id));
  }

  public Optional<ArticleData> findBySlug(String slug) {
    return Optional.ofNullable(articleReadService.findBySlug(slug));
  }

  public List<ArticleData> findArticles(List<String> ids) {
    if (ids.isEmpty()) {
      return new ArrayList<>();
    }
    return articleReadService.findArticles(ids);
  }

  /**
   * Mirrors ArticleQueryService.findRecentArticles: an empty (non-null) allow-list short-circuits
   * to zero results, exactly as the monolith does for a `favorited` user with no favorites.
   */
  public ArticleIdsData findArticleIds(
      String tag, String authorId, List<String> allowedIds, Page page) {
    if (allowedIds != null && allowedIds.isEmpty()) {
      return new ArticleIdsData(new ArrayList<>(), 0);
    }
    List<String> ids = articleReadService.queryArticleIds(tag, authorId, allowedIds, page);
    int count = articleReadService.countArticles(tag, authorId, allowedIds);
    return new ArticleIdsData(ids, count);
  }

  public List<String> findArticleIdsWithCursor(
      String tag, String authorId, List<String> allowedIds, CursorPageParameter page) {
    if (allowedIds != null && allowedIds.isEmpty()) {
      return new ArrayList<>();
    }
    return articleReadService.findArticleIdsWithCursor(tag, authorId, allowedIds, page);
  }

  public ArticleListData findUserFeed(List<String> authorIds, Page page) {
    if (authorIds.isEmpty()) {
      return new ArticleListData(new ArrayList<>(), 0);
    }
    return new ArticleListData(
        articleReadService.findArticlesOfAuthors(authorIds, page),
        articleReadService.countFeedSize(authorIds));
  }

  public List<ArticleData> findUserFeedWithCursor(
      List<String> authorIds, CursorPageParameter page) {
    if (authorIds.isEmpty()) {
      return new ArrayList<>();
    }
    return articleReadService.findArticlesOfAuthorsWithCursor(authorIds, page);
  }
}
