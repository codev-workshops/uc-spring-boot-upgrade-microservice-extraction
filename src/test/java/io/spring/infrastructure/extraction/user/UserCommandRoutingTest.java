package io.spring.infrastructure.extraction.user;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Write routing (monolith / dual-write / extracted) for the User seam. */
public class UserCommandRoutingTest {
  private final MyBatisUserRepository repository = mock(MyBatisUserRepository.class);
  private final UserServiceClient client = mock(UserServiceClient.class);
  private final LocalUserCommand local = new LocalUserCommand(repository);
  private final RemoteUserCommand remote = new RemoteUserCommand(client);
  private final DualWriteUserCommand dualWrite = new DualWriteUserCommand(local, remote);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingUserCommandPort port =
      new RoutingUserCommandPort(local, dualWrite, remote, properties, marker);

  private final User user = new User("john@jacob.com", "john", "$2a$10$hash", "", "");
  private final FollowRelation relation = new FollowRelation("u1", "u2");

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_write_to_the_monolith_only() {
    port.create(user);
    port.update(user);
    port.follow(relation);
    port.unfollow(relation);
    verify(repository, org.mockito.Mockito.times(2)).save(user);
    verify(repository).saveRelation(relation);
    verify(repository).removeRelation(relation);
    verifyNoInteractions(client);
  }

  @Test
  public void write_modes_are_ignored_while_the_flag_is_off() {
    properties.getUser().setWrite(WriteMode.EXTRACTED);
    port.create(user);
    verify(repository).save(user);
    verifyNoInteractions(client);
  }

  @Test
  public void dual_write_writes_locally_first_then_mirrors_with_the_hash() {
    enable(WriteMode.DUAL_WRITE);
    port.create(user);
    port.update(user);
    port.follow(relation);
    port.unfollow(relation);

    InOrder order = inOrder(repository, client);
    order.verify(repository).save(user);
    order.verify(client).create(user);
    order.verify(repository).save(user);
    order.verify(client).update(user);
    order.verify(repository).saveRelation(relation);
    order.verify(client).follow("u1", "u2");
    order.verify(repository).removeRelation(relation);
    order.verify(client).unfollow("u1", "u2");
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void dual_write_remote_failure_never_rolls_back_the_local_write_and_is_queued() {
    enable(WriteMode.DUAL_WRITE);
    doThrow(new UserServiceException("down")).when(client).create(user);
    doThrow(new UserServiceException("down")).when(client).follow("u1", "u2");

    port.create(user);
    port.follow(relation);
    port.unfollow(relation);

    verify(repository).save(user);
    verify(repository).saveRelation(relation);
    verify(repository).removeRelation(relation);
    List<PendingUserMirrorOperation> pending = dualWrite.pendingMirrorOperations();
    Assertions.assertEquals(2, pending.size());
    Assertions.assertEquals(PendingUserMirrorOperation.Kind.CREATE, pending.get(0).getKind());
    Assertions.assertEquals(user.getId(), pending.get(0).getUserId());
    Assertions.assertEquals(PendingUserMirrorOperation.Kind.FOLLOW, pending.get(1).getKind());
    Assertions.assertEquals("u2", pending.get(1).getTargetId());
    Assertions.assertFalse(pending.get(0).toString().contains("hash"));
    dualWrite.clearPending();
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void extracted_writes_are_remote_only_and_propagate_failures() {
    enable(WriteMode.EXTRACTED);
    port.create(user);
    port.follow(relation);
    verify(client).create(user);
    verify(client).follow("u1", "u2");
    verifyNoInteractions(repository);

    doThrow(new UserServiceException("down")).when(client).update(user);
    Assertions.assertThrows(UserServiceException.class, () -> port.update(user));
    verifyNoMoreInteractions(repository);
  }

  @Test
  public void writes_mark_the_request_for_read_after_write() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    Assertions.assertFalse(marker.writtenInThisRequest("user"));
    port.follow(relation);
    Assertions.assertTrue(marker.writtenInThisRequest("user"));
    Assertions.assertFalse(marker.writtenInThisRequest("article"));
  }

  private void enable(WriteMode mode) {
    properties.getUser().setEnabled(true);
    properties.getUser().setWrite(mode);
  }
}
