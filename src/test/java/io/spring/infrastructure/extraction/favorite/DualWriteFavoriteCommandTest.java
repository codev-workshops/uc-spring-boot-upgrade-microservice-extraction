package io.spring.infrastructure.extraction.favorite;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.spring.application.favorite.FavoriteCommandPort;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class DualWriteFavoriteCommandTest {
  private final FavoriteCommandPort local = mock(FavoriteCommandPort.class);
  private final FavoriteCommandPort remote = mock(FavoriteCommandPort.class);
  private final DualWriteFavoriteCommand command = new DualWriteFavoriteCommand(local, remote);

  @Test
  public void writes_locally_first_then_mirrors_remotely() {
    command.favorite("a", "u");
    command.unfavorite("a", "u");

    InOrder order = inOrder(local, remote);
    order.verify(local).favorite("a", "u");
    order.verify(remote).favorite("a", "u");
    order.verify(local).unfavorite("a", "u");
    order.verify(remote).unfavorite("a", "u");
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void remote_failure_is_swallowed_and_recorded_for_reconciliation() {
    doThrow(new FavoriteServiceException("down", null)).when(remote).favorite("a", "u");
    doThrow(new FavoriteServiceException("down", null)).when(remote).unfavorite("b", "u");

    command.favorite("a", "u");
    command.unfavorite("b", "u");

    verify(local).favorite("a", "u");
    verify(local).unfavorite("b", "u");
    List<PendingMirrorOperation> pending = command.pendingMirrorOperations();
    Assertions.assertEquals(2, pending.size());
    Assertions.assertEquals(PendingMirrorOperation.Kind.FAVORITE, pending.get(0).getKind());
    Assertions.assertEquals("a", pending.get(0).getArticleId());
    Assertions.assertEquals(PendingMirrorOperation.Kind.UNFAVORITE, pending.get(1).getKind());
    Assertions.assertEquals("b", pending.get(1).getArticleId());

    command.clearPending();
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void local_failure_propagates_and_is_not_mirrored() {
    doThrow(new IllegalStateException("db")).when(local).favorite("a", "u");
    Assertions.assertThrows(IllegalStateException.class, () -> command.favorite("a", "u"));
    verify(remote, org.mockito.Mockito.never()).favorite("a", "u");
  }
}
