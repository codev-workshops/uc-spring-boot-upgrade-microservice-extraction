package io.spring.infrastructure.extraction.article;

import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleQueryPort;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.article.dto.ArticleIdsPageDto;
import io.spring.application.article.dto.ArticleRowDto;
import io.spring.application.article.dto.ArticlesDto;
import io.spring.application.data.ArticleRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.stereotype.Component;

/**
 * {@link ArticleQueryPort} backed by article-service. Pure row/id translation; profiles and
 * favorites are composed by {@code ArticleQueryService}.
 */
@Component
public class RemoteArticleQueryAdapter implements ArticleQueryPort {
  private final ArticleDomainServiceClient client;

  public RemoteArticleQueryAdapter(ArticleDomainServiceClient client) {
    this.client = client;
  }

  @Override
  public Optional<ArticleRow> findById(String id) {
    return client.findById(id).map(RemoteArticleQueryAdapter::toRow);
  }

  @Override
  public Optional<ArticleRow> findBySlug(String slug) {
    return client.findBySlug(slug).map(RemoteArticleQueryAdapter::toRow);
  }

  @Override
  public List<ArticleRow> findArticles(List<String> articleIds) {
    return toRows(client.findByIds(articleIds));
  }

  @Override
  public ArticleIdPage queryArticleIds(
      String tag, String authorId, List<String> articleIds, Page page) {
    if (articleIds != null && articleIds.isEmpty()) {
      return new ArticleIdPage(new ArrayList<>(), 0);
    }
    ArticleIdsPageDto dto = client.queryIds(tag, authorId, articleIds, page);
    return new ArticleIdPage(
        dto.getArticleIds() == null ? new ArrayList<>() : new ArrayList<>(dto.getArticleIds()),
        dto.getCount() == null ? 0 : dto.getCount());
  }

  @Override
  public List<String> queryArticleIdsWithCursor(
      String tag, String authorId, List<String> articleIds, CursorPageParameter<DateTime> page) {
    if (articleIds != null && articleIds.isEmpty()) {
      return new ArrayList<>();
    }
    return new ArrayList<>(client.queryIdsWithCursor(tag, authorId, articleIds, page));
  }

  @Override
  public ArticleRowPage findArticlesOfAuthors(List<String> authorIds, Page page) {
    ArticlesDto dto = client.feed(authorIds, page);
    return new ArticleRowPage(
        toRows(dto.getArticles() == null ? new ArrayList<>() : dto.getArticles()),
        dto.getCount() == null ? 0 : dto.getCount());
  }

  @Override
  public List<ArticleRow> findArticlesOfAuthorsWithCursor(
      List<String> authorIds, CursorPageParameter<DateTime> page) {
    return toRows(client.feedWithCursor(authorIds, page));
  }

  static List<ArticleRow> toRows(List<ArticleRowDto> dtos) {
    return dtos.stream().map(RemoteArticleQueryAdapter::toRow).collect(Collectors.toList());
  }

  static ArticleRow toRow(ArticleRowDto dto) {
    return new ArticleRow(
        dto.getId(),
        dto.getSlug(),
        dto.getTitle(),
        dto.getDescription(),
        dto.getBody(),
        dto.getUserId(),
        parse(dto.getCreatedAt()),
        parse(dto.getUpdatedAt()),
        dto.getTagList() == null ? new ArrayList<>() : new ArrayList<>(dto.getTagList()));
  }

  /** Same instant and default-zone chronology as {@code DateTimeHandler} produces for a column. */
  static DateTime parse(String iso) {
    return iso == null ? null : new DateTime(ISODateTimeFormat.dateTimeParser().parseMillis(iso));
  }
}
