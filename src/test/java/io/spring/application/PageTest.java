package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PageTest {

  @Test
  public void should_use_defaults_when_no_args() {
    Page page = new Page();
    assertEquals(0, page.getOffset());
    assertEquals(20, page.getLimit());
  }

  @Test
  public void should_accept_positive_offset_and_limit() {
    Page page = new Page(5, 10);
    assertEquals(5, page.getOffset());
    assertEquals(10, page.getLimit());
  }

  @Test
  public void should_keep_default_offset_when_zero_or_negative() {
    assertEquals(0, new Page(0, 10).getOffset());
    assertEquals(0, new Page(-1, 10).getOffset());
  }

  @Test
  public void should_keep_default_limit_when_zero_or_negative() {
    assertEquals(20, new Page(0, 0).getLimit());
    assertEquals(20, new Page(0, -5).getLimit());
  }

  @Test
  public void should_cap_limit_at_max() {
    assertEquals(100, new Page(0, 100).getLimit());
    assertEquals(100, new Page(0, 101).getLimit());
    assertEquals(99, new Page(0, 99).getLimit());
  }

  @Test
  public void should_accept_limit_of_one() {
    assertEquals(1, new Page(0, 1).getLimit());
    assertEquals(1, new Page(1, 1).getOffset());
  }
}
