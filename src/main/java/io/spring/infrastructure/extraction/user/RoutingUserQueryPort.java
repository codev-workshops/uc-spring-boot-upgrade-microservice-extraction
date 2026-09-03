package io.spring.infrastructure.extraction.user;

import io.spring.application.data.UserData;
import io.spring.application.user.UserQueryPort;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.ShadowComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Picks the monolith or the remote {@link UserQueryPort} on every call according to {@code
 * extraction.user.*}: {@code shadow} returns the monolith answer and compares the remote one in the
 * background; {@code extracted} asks user-service and handles a failure per {@code fallback}. While
 * the monolith is authoritative for writes, a read that follows a write in the same request is
 * served locally. With the flag off {@link #ownsUserReads()} is false and the callers keep their
 * original SQL.
 */
@Primary
@Service
public class RoutingUserQueryPort implements UserQueryPort {
  static final String DOMAIN = "user";
  private static final Logger log = LoggerFactory.getLogger(RoutingUserQueryPort.class);

  private final LocalUserQueryAdapter monolith;
  private final RemoteUserQueryAdapter remote;
  private final ExtractionProperties properties;
  private final ShadowComparator shadow;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingUserQueryPort(
      LocalUserQueryAdapter monolith,
      RemoteUserQueryAdapter remote,
      ExtractionProperties properties,
      ShadowComparator shadow,
      ReadAfterWriteMarker readAfterWrite) {
    this.monolith = monolith;
    this.remote = remote;
    this.properties = properties;
    this.shadow = shadow;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public boolean ownsUserReads() {
    DomainRoute route = properties.getUser();
    return route.readsRemote() || route.shadows();
  }

  @Override
  public Optional<UserData> findById(String id) {
    return route(
        "findById", () -> monolith.findById(id), () -> remote.findById(id), Optional::empty);
  }

  @Override
  public Optional<UserData> findByUsername(String username) {
    return route(
        "findByUsername",
        () -> monolith.findByUsername(username),
        () -> remote.findByUsername(username),
        Optional::empty);
  }

  @Override
  public Optional<UserData> findByEmail(String email) {
    return route(
        "findByEmail",
        () -> monolith.findByEmail(email),
        () -> remote.findByEmail(email),
        Optional::empty);
  }

  @Override
  public List<UserData> findByIds(List<String> ids) {
    return route(
        "findByIds", () -> monolith.findByIds(ids), () -> remote.findByIds(ids), ArrayList::new);
  }

  <T> T route(String op, Supplier<T> local, Supplier<T> extracted, Supplier<T> empty) {
    return route(properties, shadow, readAfterWrite, log, op, local, extracted, empty);
  }

  static <T> T route(
      ExtractionProperties properties,
      ShadowComparator shadow,
      ReadAfterWriteMarker readAfterWrite,
      Logger log,
      String op,
      Supplier<T> local,
      Supplier<T> extracted,
      Supplier<T> empty) {
    DomainRoute route = properties.getUser();
    if (route.shadows()) {
      T value = local.get();
      shadow.compareAsync(DOMAIN, op, value, extracted);
      return value;
    }
    if (!route.readsRemote()) {
      return local.get();
    }
    if (route.monolithAuthoritative() && readAfterWrite.writtenInThisRequest(DOMAIN)) {
      return local.get();
    }
    try {
      return extracted.get();
    } catch (UserServiceException e) {
      log.warn(
          "user-service read failed op={} fallback={} cause={}",
          op,
          route.getFallback(),
          e.getMessage());
      switch (route.getFallback()) {
        case MONOLITH:
          return local.get();
        case EMPTY:
          return empty.get();
        case FAIL:
        default:
          throw e;
      }
    }
  }
}
