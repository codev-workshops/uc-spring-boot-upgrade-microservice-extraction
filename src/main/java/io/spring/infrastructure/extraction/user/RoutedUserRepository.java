package io.spring.infrastructure.extraction.user;

import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link UserRepository} {@code UsersApi}, {@code CurrentUserApi}, {@code ProfileApi}, {@code
 * JwtTokenFilter}, the validators, {@code UserService}, {@code AuthorizationService} and the
 * GraphQL classes see. Writes go through the routing {@link UserCommandPort}; lookups read the
 * monolith table unless user reads are extracted (or the monolith is no longer authoritative), in
 * which case they ask user-service, fall back per {@code extraction.user.fallback} and — for {@link
 * #findById}, the per-request JWT lookup — go through the 30 s {@link UserLookupCache}. Users
 * loaded from user-service carry a blank password (see {@code LoginService}).
 */
@Primary
@Repository
public class RoutedUserRepository implements UserRepository {
  private static final Logger log = LoggerFactory.getLogger(RoutedUserRepository.class);

  private final MyBatisUserRepository monolith;
  private final UserCommandPort commands;
  private final UserServiceClient client;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;
  private final UserLookupCache cache;

  public RoutedUserRepository(
      MyBatisUserRepository monolith,
      UserCommandPort commands,
      UserServiceClient client,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite,
      UserLookupCache cache) {
    this.monolith = monolith;
    this.commands = commands;
    this.client = client;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
    this.cache = cache;
  }

  @Override
  public void save(User user) {
    cache.evict(user.getId());
    if (findById(user.getId()).isPresent()) {
      commands.update(user);
    } else {
      commands.create(user);
    }
    cache.evict(user.getId());
  }

  @Override
  public Optional<User> findById(String id) {
    if (!remoteLookups()) {
      return monolith.findById(id);
    }
    return cache.get(
        id,
        () ->
            lookup(
                "findById",
                () -> monolith.findById(id),
                () -> client.findById(id).map(RemoteUserQueryAdapter::toUser)));
  }

  @Override
  public Optional<User> findByUsername(String username) {
    if (!remoteLookups()) {
      return monolith.findByUsername(username);
    }
    return lookup(
        "findByUsername",
        () -> monolith.findByUsername(username),
        () -> client.findByUsername(username).map(RemoteUserQueryAdapter::toUser));
  }

  @Override
  public Optional<User> findByEmail(String email) {
    if (!remoteLookups()) {
      return monolith.findByEmail(email);
    }
    return lookup(
        "findByEmail",
        () -> monolith.findByEmail(email),
        () -> client.findByEmail(email).map(RemoteUserQueryAdapter::toUser));
  }

  @Override
  public void saveRelation(FollowRelation followRelation) {
    commands.follow(followRelation);
  }

  @Override
  public Optional<FollowRelation> findRelation(String userId, String targetId) {
    if (!remoteLookups()) {
      return monolith.findRelation(userId, targetId);
    }
    return lookup(
        "findRelation",
        () -> monolith.findRelation(userId, targetId),
        () ->
            client.isFollowing(userId, targetId)
                ? Optional.of(new FollowRelation(userId, targetId))
                : Optional.empty());
  }

  @Override
  public void removeRelation(FollowRelation followRelation) {
    commands.unfollow(followRelation);
  }

  private boolean remoteLookups() {
    DomainRoute route = properties.getUser();
    if (!route.isEnabled()) {
      return false;
    }
    if (!route.monolithAuthoritative()) {
      return true;
    }
    return route.readsRemote() && !readAfterWrite.writtenInThisRequest(RoutingUserQueryPort.DOMAIN);
  }

  private <T> Optional<T> lookup(
      String op, Supplier<Optional<T>> local, Supplier<Optional<T>> extracted) {
    try {
      return extracted.get();
    } catch (UserServiceException e) {
      DomainRoute route = properties.getUser();
      log.warn(
          "user-service lookup failed op={} fallback={} cause={}",
          op,
          route.getFallback(),
          e.getMessage());
      switch (route.getFallback()) {
        case MONOLITH:
          return local.get();
        case EMPTY:
          return Optional.empty();
        case FAIL:
        default:
          throw e;
      }
    }
  }
}
