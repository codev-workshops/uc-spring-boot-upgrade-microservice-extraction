package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.application.CursorPager.Direction;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

public class CursorPageParameterTest {

  @Test
  public void should_use_defaults_when_no_args() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>();
    assertEquals(20, page.getLimit());
    assertEquals(21, page.getQueryLimit());
    assertNull(page.getCursor());
    assertNull(page.getDirection());
    assertFalse(page.isNext());
  }

  @Test
  public void should_set_cursor_limit_and_direction() {
    DateTime cursor = new DateTime();
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(cursor, 10, Direction.NEXT);
    assertEquals(cursor, page.getCursor());
    assertEquals(10, page.getLimit());
    assertEquals(11, page.getQueryLimit());
    assertEquals(Direction.NEXT, page.getDirection());
    assertTrue(page.isNext());
  }

  @Test
  public void should_not_be_next_when_direction_is_prev() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 10, Direction.PREV);
    assertFalse(page.isNext());
    assertEquals(Direction.PREV, page.getDirection());
  }

  @Test
  public void should_keep_default_limit_when_zero_or_negative() {
    assertEquals(20, new CursorPageParameter<DateTime>(null, 0, Direction.NEXT).getLimit());
    assertEquals(20, new CursorPageParameter<DateTime>(null, -3, Direction.NEXT).getLimit());
  }

  @Test
  public void should_accept_limit_of_one() {
    assertEquals(1, new CursorPageParameter<DateTime>(null, 1, Direction.NEXT).getLimit());
  }

  @Test
  public void should_cap_limit_at_max() {
    assertEquals(1000, new CursorPageParameter<DateTime>(null, 1000, Direction.NEXT).getLimit());
    assertEquals(1000, new CursorPageParameter<DateTime>(null, 1001, Direction.NEXT).getLimit());
    assertEquals(999, new CursorPageParameter<DateTime>(null, 999, Direction.NEXT).getLimit());
  }
}
