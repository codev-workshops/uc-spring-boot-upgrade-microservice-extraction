package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.application.CursorPager.Direction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

public class CursorPagerTest {

  private static class TestNode implements Node {
    private final DateTime time;

    TestNode(DateTime time) {
      this.time = time;
    }

    @Override
    public PageCursor getCursor() {
      return new DateTimeCursor(time);
    }
  }

  private final DateTime first = new DateTime(1000L);
  private final DateTime last = new DateTime(2000L);
  private final List<TestNode> data = Arrays.asList(new TestNode(first), new TestNode(last));

  @Test
  public void should_have_next_but_not_previous_when_direction_next_with_extra() {
    CursorPager<TestNode> pager = new CursorPager<>(data, Direction.NEXT, true);
    assertTrue(pager.hasNext());
    assertTrue(pager.isNext());
    assertFalse(pager.hasPrevious());
    assertFalse(pager.isPrevious());
  }

  @Test
  public void should_have_neither_when_direction_next_without_extra() {
    CursorPager<TestNode> pager = new CursorPager<>(data, Direction.NEXT, false);
    assertFalse(pager.hasNext());
    assertFalse(pager.hasPrevious());
  }

  @Test
  public void should_have_previous_but_not_next_when_direction_prev_with_extra() {
    CursorPager<TestNode> pager = new CursorPager<>(data, Direction.PREV, true);
    assertFalse(pager.hasNext());
    assertFalse(pager.isNext());
    assertTrue(pager.hasPrevious());
    assertTrue(pager.isPrevious());
  }

  @Test
  public void should_have_neither_when_direction_prev_without_extra() {
    CursorPager<TestNode> pager = new CursorPager<>(data, Direction.PREV, false);
    assertFalse(pager.hasNext());
    assertFalse(pager.hasPrevious());
  }

  @Test
  public void should_expose_start_and_end_cursor_from_data() {
    CursorPager<TestNode> pager = new CursorPager<>(data, Direction.NEXT, false);
    assertEquals(data, pager.getData());
    assertEquals(String.valueOf(first.getMillis()), pager.getStartCursor().toString());
    assertEquals(String.valueOf(last.getMillis()), pager.getEndCursor().toString());
  }

  @Test
  public void should_return_null_cursors_when_empty() {
    CursorPager<TestNode> pager = new CursorPager<>(new ArrayList<>(), Direction.NEXT, false);
    assertNull(pager.getStartCursor());
    assertNull(pager.getEndCursor());
  }
}
