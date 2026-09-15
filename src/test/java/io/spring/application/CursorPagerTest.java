package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

public class CursorPagerTest {
  private final PageCursor<String> firstCursor = new PageCursor<>("first") {};
  private final PageCursor<String> secondCursor = new PageCursor<>("second") {};

  @Test
  public void nextPageWithExtraDataHasNextOnly() {
    CursorPager<Node> pager =
        new CursorPager<>(
            Arrays.asList(new TestNode(firstCursor), new TestNode(secondCursor)),
            CursorPager.Direction.NEXT,
            true);

    assertTrue(pager.hasNext());
    assertFalse(pager.hasPrevious());
    assertTrue(pager.isNext());
    assertFalse(pager.isPrevious());
  }

  @Test
  public void nextPageWithoutExtraDataHasNoMorePages() {
    CursorPager<Node> pager =
        new CursorPager<>(
            Collections.singletonList(new TestNode(firstCursor)),
            CursorPager.Direction.NEXT,
            false);

    assertFalse(pager.hasNext());
    assertFalse(pager.hasPrevious());
  }

  @Test
  public void previousPageWithExtraDataHasPreviousOnly() {
    CursorPager<Node> pager =
        new CursorPager<>(
            Collections.singletonList(new TestNode(firstCursor)), CursorPager.Direction.PREV, true);

    assertFalse(pager.hasNext());
    assertTrue(pager.hasPrevious());
    assertFalse(pager.isNext());
    assertTrue(pager.isPrevious());
  }

  @Test
  public void emptyPagerHasNoCursors() {
    CursorPager<Node> pager =
        new CursorPager<>(Collections.emptyList(), CursorPager.Direction.NEXT, false);

    assertNull(pager.getStartCursor());
    assertNull(pager.getEndCursor());
  }

  @Test
  public void cursorsComeFromFirstAndLastDataItems() {
    CursorPager<Node> pager =
        new CursorPager<>(
            Arrays.asList(new TestNode(firstCursor), new TestNode(secondCursor)),
            CursorPager.Direction.NEXT,
            false);

    assertSame(firstCursor, pager.getStartCursor());
    assertSame(secondCursor, pager.getEndCursor());
  }

  private static class TestNode implements Node {
    private final PageCursor cursor;

    private TestNode(PageCursor cursor) {
      this.cursor = cursor;
    }

    @Override
    public PageCursor getCursor() {
      return cursor;
    }
  }
}
