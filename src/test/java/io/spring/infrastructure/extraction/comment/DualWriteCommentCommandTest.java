package io.spring.infrastructure.extraction.comment;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.spring.application.comment.CommentCommandPort;
import io.spring.core.comment.Comment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class DualWriteCommentCommandTest {
  private final CommentCommandPort local = mock(CommentCommandPort.class);
  private final CommentCommandPort remote = mock(CommentCommandPort.class);
  private final DualWriteCommentCommand command = new DualWriteCommentCommand(local, remote);
  private final Comment comment = new Comment("body", "u", "a");

  @Test
  public void writes_the_monolith_first_then_mirrors_the_same_row() {
    command.create(comment);
    command.delete("a", comment.getId());

    InOrder order = inOrder(local, remote);
    order.verify(local).create(comment);
    order.verify(remote).create(comment);
    order.verify(local).delete("a", comment.getId());
    order.verify(remote).delete("a", comment.getId());
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void remote_failures_are_recorded_and_never_surfaced() {
    doThrow(new CommentServiceException("down", null)).when(remote).create(comment);
    doThrow(new CommentServiceException("down", null)).when(remote).delete("a", "c9");

    command.create(comment);
    command.delete("a", "c9");

    Assertions.assertEquals(2, command.pendingMirrorOperations().size());
    PendingCommentMirrorOperation first = command.pendingMirrorOperations().get(0);
    Assertions.assertEquals(PendingCommentMirrorOperation.Kind.CREATE, first.getKind());
    Assertions.assertEquals(comment.getId(), first.getCommentId());
    Assertions.assertEquals("a", first.getArticleId());
    Assertions.assertEquals(
        PendingCommentMirrorOperation.Kind.DELETE,
        command.pendingMirrorOperations().get(1).getKind());
    command.clearPending();
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void local_failures_propagate_and_skip_the_mirror() {
    doThrow(new IllegalStateException("db")).when(local).create(comment);
    Assertions.assertThrows(IllegalStateException.class, () -> command.create(comment));
    verify(remote, never()).create(comment);
  }
}
