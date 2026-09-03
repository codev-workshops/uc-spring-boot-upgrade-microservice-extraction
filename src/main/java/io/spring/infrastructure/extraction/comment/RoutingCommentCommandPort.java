package io.spring.infrastructure.extraction.comment;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Selects the local, dual-write or remote {@link CommentCommandPort} per call from {@code
 * extraction.comment.write} and marks the request so a following read is served by the
 * authoritative store.
 */
@Primary
@Service
public class RoutingCommentCommandPort implements CommentCommandPort {
  private final LocalCommentCommand local;
  private final DualWriteCommentCommand dualWrite;
  private final RemoteCommentCommand remote;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingCommentCommandPort(
      LocalCommentCommand local,
      DualWriteCommentCommand dualWrite,
      RemoteCommentCommand remote,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite) {
    this.local = local;
    this.dualWrite = dualWrite;
    this.remote = remote;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public void create(Comment comment) {
    select().create(comment);
    readAfterWrite.markWritten(RoutingCommentQueryPort.DOMAIN);
  }

  @Override
  public void delete(String articleId, String commentId) {
    select().delete(articleId, commentId);
    readAfterWrite.markWritten(RoutingCommentQueryPort.DOMAIN);
  }

  CommentCommandPort select() {
    DomainRoute route = properties.getComment();
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
