package io.spring.infrastructure.extraction.user;

import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Selects the local, dual-write or remote {@link UserCommandPort} per call from {@code
 * extraction.user.write} and marks the request so a following read is served by the authoritative
 * store.
 */
@Primary
@Service
public class RoutingUserCommandPort implements UserCommandPort {
  private final LocalUserCommand local;
  private final DualWriteUserCommand dualWrite;
  private final RemoteUserCommand remote;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingUserCommandPort(
      LocalUserCommand local,
      DualWriteUserCommand dualWrite,
      RemoteUserCommand remote,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite) {
    this.local = local;
    this.dualWrite = dualWrite;
    this.remote = remote;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public void create(User user) {
    select().create(user);
    readAfterWrite.markWritten(RoutingUserQueryPort.DOMAIN);
  }

  @Override
  public void update(User user) {
    select().update(user);
    readAfterWrite.markWritten(RoutingUserQueryPort.DOMAIN);
  }

  @Override
  public void follow(FollowRelation relation) {
    select().follow(relation);
    readAfterWrite.markWritten(RoutingUserQueryPort.DOMAIN);
  }

  @Override
  public void unfollow(FollowRelation relation) {
    select().unfollow(relation);
    readAfterWrite.markWritten(RoutingUserQueryPort.DOMAIN);
  }

  UserCommandPort select() {
    DomainRoute route = properties.getUser();
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
