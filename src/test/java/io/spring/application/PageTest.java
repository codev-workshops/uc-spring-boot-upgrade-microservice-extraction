package io.spring.application;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PageTest {

  @Test
  public void should_keep_valid_offset_and_limit() {
    Page page = new Page(5, 30);

    Assertions.assertEquals(5, page.getOffset());
    Assertions.assertEquals(30, page.getLimit());
  }

  @Test
  public void should_cap_limit_and_ignore_negative_values() {
    Assertions.assertEquals(100, new Page(0, 500).getLimit());
    Page page = new Page(-3, -1);
    Assertions.assertEquals(0, page.getOffset());
    Assertions.assertEquals(20, page.getLimit());
  }

  @Test
  public void should_compare_pages_by_value() {
    Assertions.assertEquals(new Page(1, 10), new Page(1, 10));
    Assertions.assertEquals(new Page(1, 10).hashCode(), new Page(1, 10).hashCode());
    Assertions.assertNotEquals(new Page(1, 10), new Page(2, 10));
    Assertions.assertTrue(new Page(1, 10).toString().contains("offset=1"));
  }
}
