package io.spring.infrastructure.extraction;

import java.util.function.Supplier;

/** Hook used by the routing ports in {@code read=shadow} mode. */
public interface ShadowComparator {
  <T> void compareAsync(String domain, String op, T monolithResult, Supplier<T> extracted);
}
