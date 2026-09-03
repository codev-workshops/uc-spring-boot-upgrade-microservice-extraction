package io.spring.infrastructure.extraction;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.infrastructure.extraction.favorite.FavoriteServiceException;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LoggingShadowComparatorTest {
  private final LoggingShadowComparator comparator = new LoggingShadowComparator(Runnable::run);

  @Test
  public void equal_results_in_different_order_are_not_a_mismatch() {
    comparator.compareAsync(
        "favorite",
        "counts",
        Arrays.asList(new ArticleFavoriteCount("a", 1), new ArticleFavoriteCount("b", 0)),
        () -> Arrays.asList(new ArticleFavoriteCount("b", 0), new ArticleFavoriteCount("a", 1)));
    comparator.compareAsync(
        "favorite",
        "set",
        new HashSet<>(Arrays.asList("x", "y")),
        () -> new HashSet<>(Arrays.asList("y", "x")));
    Assertions.assertEquals(0, comparator.mismatchCount());
  }

  @Test
  public void different_results_are_counted_as_mismatch() {
    comparator.compareAsync("favorite", "count", 3, () -> 2);
    Assertions.assertEquals(1, comparator.mismatchCount());
  }

  @Test
  public void remote_failure_is_counted_and_swallowed() {
    comparator.compareAsync(
        "favorite",
        "count",
        3,
        () -> {
          throw new FavoriteServiceException("down", null);
        });
    Assertions.assertEquals(1, comparator.errorCount());
    Assertions.assertEquals(0, comparator.mismatchCount());
  }
}
