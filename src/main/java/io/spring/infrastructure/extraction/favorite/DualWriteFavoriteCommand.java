package io.spring.infrastructure.extraction.favorite;

import io.spring.application.favorite.FavoriteCommandPort;
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
public class DualWriteFavoriteCommand implements FavoriteCommandPort {
  private static final Logger log = LoggerFactory.getLogger(DualWriteFavoriteCommand.class);

  private final FavoriteCommandPort local;
  private final FavoriteCommandPort remote;
  private final ConcurrentLinkedQueue<PendingMirrorOperation> pending =
      new ConcurrentLinkedQueue<>();

  @Autowired
  public DualWriteFavoriteCommand(LocalFavoriteCommand local, RemoteFavoriteCommand remote) {
    this((FavoriteCommandPort) local, remote);
  }

  DualWriteFavoriteCommand(FavoriteCommandPort local, FavoriteCommandPort remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public void favorite(String articleId, String userId) {
    local.favorite(articleId, userId);
    mirror(PendingMirrorOperation.Kind.FAVORITE, articleId, userId);
  }

  @Override
  public void unfavorite(String articleId, String userId) {
    local.unfavorite(articleId, userId);
    mirror(PendingMirrorOperation.Kind.UNFAVORITE, articleId, userId);
  }

  private void mirror(PendingMirrorOperation.Kind kind, String articleId, String userId) {
    try {
      if (kind == PendingMirrorOperation.Kind.FAVORITE) {
        remote.favorite(articleId, userId);
      } else {
        remote.unfavorite(articleId, userId);
      }
    } catch (RuntimeException e) {
      pending.add(
          new PendingMirrorOperation(kind, articleId, userId, Instant.now(), e.getMessage()));
      log.warn(
          "favorite mirror failed kind={} articleId={} userId={} pending={} cause={}",
          kind,
          articleId,
          userId,
          pending.size(),
          e.getMessage());
    }
  }

  /** Snapshot of writes still to be replayed against favorite-service. */
  public List<PendingMirrorOperation> pendingMirrorOperations() {
    return new ArrayList<>(pending);
  }

  public void clearPending() {
    pending.clear();
  }
}
