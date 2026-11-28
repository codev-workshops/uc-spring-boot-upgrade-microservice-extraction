package io.spring.application;

import io.spring.application.CursorPager.Direction;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CursorPageParameterTest {

  @Test
  public void should_keep_valid_limit_and_query_one_extra() {
    DateTime cursor = new DateTime(2020, 1, 1, 0, 0);
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(cursor, 10, Direction.NEXT);

    Assertions.assertEquals(10, page.getLimit());
    Assertions.assertEquals(11, page.getQueryLimit());
    Assertions.assertEquals(cursor, page.getCursor());
    Assertions.assertTrue(page.isNext());
  }

  @Test
  public void should_cap_limit_at_maximum() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 5000, Direction.PREV);

    Assertions.assertEquals(1000, page.getLimit());
    Assertions.assertFalse(page.isNext());
  }

  @Test
  public void should_use_default_limit_for_non_positive_value() {
    Assertions.assertEquals(20, new CursorPageParameter<>(null, 0, Direction.NEXT).getLimit());
    Assertions.assertEquals(20, new CursorPageParameter<>(null, -1, Direction.NEXT).getLimit());
  }

  @Test
  public void should_compare_parameters_by_value() {
    CursorPageParameter<String> a = new CursorPageParameter<>("c", 5, Direction.NEXT);
    CursorPageParameter<String> b = new CursorPageParameter<>("c", 5, Direction.NEXT);
    CursorPageParameter<String> c = new CursorPageParameter<>("c", 5, Direction.PREV);

    Assertions.assertEquals(a, b);
    Assertions.assertEquals(a.hashCode(), b.hashCode());
    Assertions.assertNotEquals(a, c);
    Assertions.assertTrue(a.toString().contains("limit=5"));
  }
}
