package io.spring.infrastructure.extraction.user;

import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
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
 * logged (never with the hash) and queued in {@link #pendingMirrorOperations()} for reconciliation.
 */
@Component
public class DualWriteUserCommand implements UserCommandPort {
  private static final Logger log = LoggerFactory.getLogger(DualWriteUserCommand.class);

  private final UserCommandPort local;
  private final UserCommandPort remote;
  private final ConcurrentLinkedQueue<PendingUserMirrorOperation> pending =
      new ConcurrentLinkedQueue<>();

  @Autowired
  public DualWriteUserCommand(LocalUserCommand local, RemoteUserCommand remote) {
    this((UserCommandPort) local, remote);
  }

  DualWriteUserCommand(UserCommandPort local, UserCommandPort remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public void create(User user) {
    local.create(user);
    mirror(PendingUserMirrorOperation.Kind.CREATE, user.getId(), null, () -> remote.create(user));
  }

  @Override
  public void update(User user) {
    local.update(user);
    mirror(PendingUserMirrorOperation.Kind.UPDATE, user.getId(), null, () -> remote.update(user));
  }

  @Override
  public void follow(FollowRelation relation) {
    local.follow(relation);
    mirror(
        PendingUserMirrorOperation.Kind.FOLLOW,
        relation.getUserId(),
        relation.getTargetId(),
        () -> remote.follow(relation));
  }

  @Override
  public void unfollow(FollowRelation relation) {
    local.unfollow(relation);
    mirror(
        PendingUserMirrorOperation.Kind.UNFOLLOW,
        relation.getUserId(),
        relation.getTargetId(),
        () -> remote.unfollow(relation));
  }

  private void mirror(
      PendingUserMirrorOperation.Kind kind, String userId, String targetId, Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      pending.add(
          new PendingUserMirrorOperation(kind, userId, targetId, Instant.now(), e.getMessage()));
      log.warn(
          "user mirror failed kind={} userId={} targetId={} pending={} cause={}",
          kind,
          userId,
          targetId,
          pending.size(),
          e.getMessage());
    }
  }

  /** Snapshot of writes still to be replayed against user-service. */
  public List<PendingUserMirrorOperation> pendingMirrorOperations() {
    return new ArrayList<>(pending);
  }

  public void clearPending() {
    pending.clear();
  }
}
