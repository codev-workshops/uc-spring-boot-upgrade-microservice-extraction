package io.spring.infrastructure.extraction.user;

import io.spring.core.user.User;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Tiny in-process cache (30 s TTL, no extra dependency) for {@code UserRepository.findById} when
 * the lookup is served by user-service, so the {@code JwtTokenFilter} hot path does not make one
 * HTTP call per request. Only positive hits are cached; a write to the user evicts it.
 */
@Component
public class UserLookupCache {
  static final long TTL_MILLIS = 30_000L;

  private final LongSupplier clock;
  private final Map<String, Entry> entries = new HashMap<>();

  public UserLookupCache() {
    this(System::currentTimeMillis);
  }

  UserLookupCache(LongSupplier clock) {
    this.clock = clock;
  }

  public Optional<User> get(String id, Supplier<Optional<User>> loader) {
    long now = clock.getAsLong();
    synchronized (entries) {
      Entry entry = entries.get(id);
      if (entry != null && entry.expiresAt > now) {
        return Optional.of(entry.user);
      }
      entries.remove(id);
    }
    Optional<User> loaded = loader.get();
    loaded.ifPresent(
        user -> {
          synchronized (entries) {
            entries.put(id, new Entry(user, now + TTL_MILLIS));
          }
        });
    return loaded;
  }

  public void evict(String id) {
    synchronized (entries) {
      entries.remove(id);
    }
  }

  public void clear() {
    synchronized (entries) {
      entries.clear();
    }
  }

  private static final class Entry {
    private final User user;
    private final long expiresAt;

    private Entry(User user, long expiresAt) {
      this.user = user;
      this.expiresAt = expiresAt;
    }
  }
}
