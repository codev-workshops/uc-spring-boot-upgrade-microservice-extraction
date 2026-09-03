package io.spring.infrastructure.extraction.comment;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
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
public class DualWriteCommentCommand implements CommentCommandPort {
  private static final Logger log = LoggerFactory.getLogger(DualWriteCommentCommand.class);

  private final CommentCommandPort local;
  private final CommentCommandPort remote;
  private final ConcurrentLinkedQueue<PendingCommentMirrorOperation> pending =
      new ConcurrentLinkedQueue<>();

  @Autowired
  public DualWriteCommentCommand(LocalCommentCommand local, RemoteCommentCommand remote) {
    this((CommentCommandPort) local, remote);
  }

  DualWriteCommentCommand(CommentCommandPort local, CommentCommandPort remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public void create(Comment comment) {
    local.create(comment);
    mirror(
        PendingCommentMirrorOperation.Kind.CREATE,
        comment.getArticleId(),
        comment.getId(),
        () -> remote.create(comment));
  }

  @Override
  public void delete(String articleId, String commentId) {
    local.delete(articleId, commentId);
    mirror(
        PendingCommentMirrorOperation.Kind.DELETE,
        articleId,
        commentId,
        () -> remote.delete(articleId, commentId));
  }

  private void mirror(
      PendingCommentMirrorOperation.Kind kind, String articleId, String commentId, Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      pending.add(
          new PendingCommentMirrorOperation(
              kind, articleId, commentId, Instant.now(), e.getMessage()));
      log.warn(
          "comment mirror failed kind={} articleId={} commentId={} pending={} cause={}",
          kind,
          articleId,
          commentId,
          pending.size(),
          e.getMessage());
    }
  }

  /** Snapshot of writes still to be replayed against comment-service. */
  public List<PendingCommentMirrorOperation> pendingMirrorOperations() {
    return new ArrayList<>(pending);
  }

  public void clearPending() {
    pending.clear();
  }
}
