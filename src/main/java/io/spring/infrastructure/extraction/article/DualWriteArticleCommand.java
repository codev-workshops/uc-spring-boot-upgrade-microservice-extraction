package io.spring.infrastructure.extraction.article;

import io.spring.application.article.ArticleCommandPort;
import io.spring.core.article.Article;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Monolith-first dual write: the local write is authoritative and any remote failure is swallowed,
 * logged and queued in {@link #pendingMirrorOperations()} for reconciliation.
 */
@Component
public class DualWriteArticleCommand implements ArticleCommandPort {
  private static final Logger log = LoggerFactory.getLogger(DualWriteArticleCommand.class);

  private final ArticleCommandPort local;
  private final ArticleCommandPort remote;
  private final ConcurrentLinkedQueue<PendingArticleMirrorOperation> pending =
      new ConcurrentLinkedQueue<>();

  @Autowired
  public DualWriteArticleCommand(LocalArticleCommand local, RemoteArticleCommand remote) {
    this((ArticleCommandPort) local, remote);
  }

  DualWriteArticleCommand(ArticleCommandPort local, ArticleCommandPort remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public void create(Article article) {
    local.create(article);
    mirror(
        PendingArticleMirrorOperation.Kind.CREATE, article.getId(), () -> remote.create(article));
  }

  @Override
  public void update(Article article) {
    local.update(article);
    mirror(
        PendingArticleMirrorOperation.Kind.UPDATE, article.getId(), () -> remote.update(article));
  }

  @Override
  public void delete(Article article) {
    local.delete(article);
    mirror(
        PendingArticleMirrorOperation.Kind.DELETE, article.getId(), () -> remote.delete(article));
  }

  private void mirror(PendingArticleMirrorOperation.Kind kind, String articleId, Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      pending.add(
          new PendingArticleMirrorOperation(kind, articleId, Instant.now(), e.getMessage()));
      log.warn(
          "article mirror failed kind={} articleId={} pending={} cause={}",
          kind,
          articleId,
          pending.size(),
          e.getMessage());
    }
  }

  /** Snapshot of writes still to be replayed against article-service. */
  public List<PendingArticleMirrorOperation> pendingMirrorOperations() {
    return new ArrayList<>(pending);
  }

  public void clearPending() {
    pending.clear();
  }
}
