package io.spring.infrastructure.extraction.article;

import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleQueryPort;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleRow;
import io.spring.infrastructure.mybatis.readservice.ArticleRowReadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

/** {@link ArticleQueryPort} over the monolith {@code articles} tables. */
@Component
public class LocalArticleQueryAdapter implements ArticleQueryPort {
  private final ArticleRowReadService readService;

  public LocalArticleQueryAdapter(ArticleRowReadService readService) {
    this.readService = readService;
  }

  @Override
  public Optional<ArticleRow> findById(String id) {
    return Optional.ofNullable(readService.findById(id));
  }

  @Override
  public Optional<ArticleRow> findBySlug(String slug) {
    return Optional.ofNullable(readService.findBySlug(slug));
  }

  @Override
  public List<ArticleRow> findArticles(List<String> articleIds) {
    return articleIds.isEmpty() ? new ArrayList<>() : readService.findArticles(articleIds);
  }

  @Override
  public ArticleIdPage queryArticleIds(
      String tag, String authorId, List<String> articleIds, Page page) {
    if (articleIds != null && articleIds.isEmpty()) {
      return new ArticleIdPage(new ArrayList<>(), 0);
    }
    return new ArticleIdPage(
        readService.queryArticleIds(tag, authorId, articleIds, page),
        readService.countArticleIds(tag, authorId, articleIds));
  }

  @Override
  public List<String> queryArticleIdsWithCursor(
      String tag, String authorId, List<String> articleIds, CursorPageParameter<DateTime> page) {
    if (articleIds != null && articleIds.isEmpty()) {
      return new ArrayList<>();
    }
    return readService.queryArticleIdsWithCursor(tag, authorId, articleIds, page);
  }

  @Override
  public ArticleRowPage findArticlesOfAuthors(List<String> authorIds, Page page) {
    if (authorIds.isEmpty()) {
      return new ArticleRowPage(new ArrayList<>(), 0);
    }
    return new ArticleRowPage(
        readService.findArticlesOfAuthors(authorIds, page),
        readService.countArticlesOfAuthors(authorIds));
  }

  @Override
  public List<ArticleRow> findArticlesOfAuthorsWithCursor(
      List<String> authorIds, CursorPageParameter<DateTime> page) {
    return authorIds.isEmpty()
        ? new ArrayList<>()
        : readService.findArticlesOfAuthorsWithCursor(authorIds, page);
  }
}
