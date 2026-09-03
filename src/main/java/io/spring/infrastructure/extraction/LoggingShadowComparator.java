package io.spring.infrastructure.extraction;

import io.spring.application.data.ArticleFavoriteCount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs the extracted path on a small bounded executor (oldest work is discarded under pressure so
 * shadowing never blocks a request), normalises both results and logs a WARN on mismatch.
 */
@Component
public class LoggingShadowComparator implements ShadowComparator {
  private static final Logger log = LoggerFactory.getLogger(LoggingShadowComparator.class);

  private final Executor executor;
  private final ThreadPoolExecutor ownedExecutor;
  private final AtomicLong mismatches = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();

  @Autowired
  public LoggingShadowComparator() {
    this.ownedExecutor =
        new ThreadPoolExecutor(
            1,
            2,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    this.executor = ownedExecutor;
  }

  public LoggingShadowComparator(Executor executor) {
    this.executor = executor;
    this.ownedExecutor = null;
  }

  @Override
  public <T> void compareAsync(String domain, String op, T monolithResult, Supplier<T> extracted) {
    Object expected = normalise(monolithResult);
    executor.execute(
        () -> {
          try {
            Object actual = normalise(extracted.get());
            if (!Objects.equals(expected, actual)) {
              mismatches.incrementAndGet();
              log.warn(
                  "shadow mismatch domain={} op={} monolith={} extracted={}",
                  domain,
                  op,
                  expected,
                  actual);
            }
          } catch (RuntimeException e) {
            errors.incrementAndGet();
            log.warn(
                "shadow call failed domain={} op={} error={}",
                domain,
                op,
                e.getClass().getSimpleName());
          }
        });
  }

  public long mismatchCount() {
    return mismatches.get();
  }

  public long errorCount() {
    return errors.get();
  }

  static Object normalise(Object value) {
    if (value instanceof Collection) {
      List<Object> items = new ArrayList<>((Collection<?>) value);
      items.sort(Comparator.comparing(LoggingShadowComparator::sortKey));
      return items;
    }
    return value;
  }

  private static String sortKey(Object item) {
    if (item instanceof ArticleFavoriteCount) {
      return ((ArticleFavoriteCount) item).getId();
    }
    return String.valueOf(item);
  }

  @PreDestroy
  public void shutdown() {
    if (ownedExecutor != null) {
      ownedExecutor.shutdownNow();
    }
  }
}
