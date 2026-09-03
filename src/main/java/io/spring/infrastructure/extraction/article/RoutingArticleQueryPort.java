package io.spring.infrastructure.extraction.article;

import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleQueryPort;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleRow;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.ShadowComparator;
import io.spring.infrastructure.extraction.tag.ArticleServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Picks the monolith or the remote {@link ArticleQueryPort} on every call according to {@code
 * extraction.article.*}. In {@code shadow} mode the monolith answer is returned and the remote one
 * is compared in the background; in {@code extracted} mode a remote failure is handled per {@code
 * fallback}. While the monolith is still authoritative for writes, a read that follows a write in
 * the same request is always served locally so the response reflects what was just written.
 *
 * <p>{@link #ownsArticleReads()} is {@code true} only while a remote read may be attempted
 * (extracted or shadow). With the flag off, {@code ArticleQueryService} keeps using its original
 * {@code ArticleReadService} SQL and this port is never called.
 */
@Primary
@Service
public class RoutingArticleQueryPort implements ArticleQueryPort {
  static final String DOMAIN = "article";
  private static final Logger log = LoggerFactory.getLogger(RoutingArticleQueryPort.class);

  private final LocalArticleQueryAdapter monolith;
  private final RemoteArticleQueryAdapter remote;
  private final ExtractionProperties properties;
  private final ShadowComparator shadow;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingArticleQueryPort(
      LocalArticleQueryAdapter monolith,
      RemoteArticleQueryAdapter remote,
      ExtractionProperties properties,
      ShadowComparator shadow,
      ReadAfterWriteMarker readAfterWrite) {
    this.monolith = monolith;
    this.remote = remote;
    this.properties = properties;
    this.shadow = shadow;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public boolean ownsArticleReads() {
    DomainRoute route = properties.getArticle();
    return route.readsRemote() || route.shadows();
  }

  @Override
  public Optional<ArticleRow> findById(String id) {
    return route(
        "findById", () -> monolith.findById(id), () -> remote.findById(id), Optional::empty);
  }

  @Override
  public Optional<ArticleRow> findBySlug(String slug) {
    return route(
        "findBySlug",
        () -> monolith.findBySlug(slug),
        () -> remote.findBySlug(slug),
        Optional::empty);
  }

  @Override
  public List<ArticleRow> findArticles(List<String> articleIds) {
    return route(
        "findArticles",
        () -> monolith.findArticles(articleIds),
        () -> remote.findArticles(articleIds),
        ArrayList::new);
  }

  @Override
  public ArticleIdPage queryArticleIds(
      String tag, String authorId, List<String> articleIds, Page page) {
    return route(
        "queryArticleIds",
        () -> monolith.queryArticleIds(tag, authorId, articleIds, page),
        () -> remote.queryArticleIds(tag, authorId, articleIds, page),
        () -> new ArticleIdPage(new ArrayList<>(), 0));
  }

  @Override
  public List<String> queryArticleIdsWithCursor(
      String tag, String authorId, List<String> articleIds, CursorPageParameter<DateTime> page) {
    return route(
        "queryArticleIdsWithCursor",
        () -> monolith.queryArticleIdsWithCursor(tag, authorId, articleIds, page),
        () -> remote.queryArticleIdsWithCursor(tag, authorId, articleIds, page),
        ArrayList::new);
  }

  @Override
  public ArticleRowPage findArticlesOfAuthors(List<String> authorIds, Page page) {
    return route(
        "findArticlesOfAuthors",
        () -> monolith.findArticlesOfAuthors(authorIds, page),
        () -> remote.findArticlesOfAuthors(authorIds, page),
        () -> new ArticleRowPage(new ArrayList<>(), 0));
  }

  @Override
  public List<ArticleRow> findArticlesOfAuthorsWithCursor(
      List<String> authorIds, CursorPageParameter<DateTime> page) {
    return route(
        "findArticlesOfAuthorsWithCursor",
        () -> monolith.findArticlesOfAuthorsWithCursor(authorIds, page),
        () -> remote.findArticlesOfAuthorsWithCursor(authorIds, page),
        ArrayList::new);
  }

  private <T> T route(String op, Supplier<T> local, Supplier<T> extracted, Supplier<T> empty) {
    DomainRoute route = properties.getArticle();
    if (route.shadows()) {
      T value = local.get();
      shadow.compareAsync(DOMAIN, op, value, extracted);
      return value;
    }
    if (!route.readsRemote()) {
      return local.get();
    }
    if (route.monolithAuthoritative() && readAfterWrite.writtenInThisRequest(DOMAIN)) {
      return local.get();
    }
    try {
      return extracted.get();
    } catch (ArticleServiceException e) {
      return fallback(route, op, local, empty, e);
    }
  }

  private <T> T fallback(
      DomainRoute route,
      String op,
      Supplier<T> local,
      Supplier<T> empty,
      ArticleServiceException cause) {
    log.warn(
        "article-service read failed op={} fallback={} cause={}",
        op,
        route.getFallback(),
        cause.getMessage());
    switch (route.getFallback()) {
      case MONOLITH:
        return local.get();
      case EMPTY:
        return empty.get();
      case FAIL:
      default:
        throw cause;
    }
  }
}
