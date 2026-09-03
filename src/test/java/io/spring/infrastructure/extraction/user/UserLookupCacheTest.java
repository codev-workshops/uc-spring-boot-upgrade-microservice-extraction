package io.spring.infrastructure.extraction.user;

import io.spring.core.user.User;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserLookupCacheTest {
  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final UserLookupCache cache = new UserLookupCache(now::get);
  private final User user = new User("u1", "john@jacob.com", "john", "", "", "");
  private final AtomicInteger loads = new AtomicInteger();

  @Test
  public void positive_lookups_are_served_from_cache_for_thirty_seconds() {
    Assertions.assertEquals(30_000L, UserLookupCache.TTL_MILLIS);
    Assertions.assertSame(user, cache.get("u1", this::load).get());
    Assertions.assertSame(user, cache.get("u1", this::load).get());
    now.addAndGet(UserLookupCache.TTL_MILLIS - 1);
    Assertions.assertSame(user, cache.get("u1", this::load).get());
    Assertions.assertEquals(1, loads.get());

    now.addAndGet(1);
    Assertions.assertSame(user, cache.get("u1", this::load).get());
    Assertions.assertEquals(2, loads.get());
  }

  @Test
  public void misses_are_not_cached() {
    Assertions.assertEquals(Optional.empty(), cache.get("nope", this::miss));
    Assertions.assertEquals(Optional.empty(), cache.get("nope", this::miss));
    Assertions.assertEquals(2, loads.get());
  }

  @Test
  public void evict_and_clear_force_a_reload() {
    cache.get("u1", this::load);
    cache.evict("u1");
    cache.get("u1", this::load);
    cache.clear();
    cache.get("u1", this::load);
    Assertions.assertEquals(3, loads.get());
  }

  @Test
  public void loader_failures_propagate_and_leave_nothing_cached() {
    Assertions.assertThrows(
        UserServiceException.class,
        () ->
            cache.get(
                "u1",
                () -> {
                  throw new UserServiceException("down");
                }));
    Assertions.assertSame(user, cache.get("u1", this::load).get());
    Assertions.assertEquals(1, loads.get());
  }

  private Optional<User> load() {
    loads.incrementAndGet();
    return Optional.of(user);
  }

  private Optional<User> miss() {
    loads.incrementAndGet();
    return Optional.empty();
  }
}
