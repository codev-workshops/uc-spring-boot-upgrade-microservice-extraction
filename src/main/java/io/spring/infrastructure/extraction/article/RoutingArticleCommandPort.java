package io.spring.infrastructure.extraction.article;

import io.spring.application.article.ArticleCommandPort;
import io.spring.core.article.Article;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Selects the local, dual-write or remote {@link ArticleCommandPort} per call from {@code
 * extraction.article.write} and marks the request so a following read is served by the
 * authoritative store.
 */
@Primary
@Service
public class RoutingArticleCommandPort implements ArticleCommandPort {
  private final LocalArticleCommand local;
  private final DualWriteArticleCommand dualWrite;
  private final RemoteArticleCommand remote;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingArticleCommandPort(
      LocalArticleCommand local,
      DualWriteArticleCommand dualWrite,
      RemoteArticleCommand remote,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite) {
    this.local = local;
    this.dualWrite = dualWrite;
    this.remote = remote;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public void create(Article article) {
    select().create(article);
    readAfterWrite.markWritten(RoutingArticleQueryPort.DOMAIN);
  }

  @Override
  public void update(Article article) {
    select().update(article);
    readAfterWrite.markWritten(RoutingArticleQueryPort.DOMAIN);
  }

  @Override
  public void delete(Article article) {
    select().delete(article);
    readAfterWrite.markWritten(RoutingArticleQueryPort.DOMAIN);
  }

  ArticleCommandPort select() {
    DomainRoute route = properties.getArticle();
    if (!route.isEnabled()) {
      return local;
    }
    switch (route.getWrite()) {
      case DUAL_WRITE:
        return dualWrite;
      case EXTRACTED:
        return remote;
      case MONOLITH:
      default:
        return local;
    }
  }
}
