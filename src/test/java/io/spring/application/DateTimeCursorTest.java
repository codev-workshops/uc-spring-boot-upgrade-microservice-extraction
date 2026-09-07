package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;

public class DateTimeCursorTest {

  @Test
  public void should_render_millis_as_string() {
    DateTime time = new DateTime(1234567890123L);
    assertEquals("1234567890123", new DateTimeCursor(time).toString());
    assertEquals(time, new DateTimeCursor(time).getData());
  }

  @Test
  public void should_parse_millis_to_utc_datetime() {
    DateTime parsed = DateTimeCursor.parse("1234567890123");
    assertEquals(1234567890123L, parsed.getMillis());
    assertEquals(DateTimeZone.UTC, parsed.getZone());
  }

  @Test
  public void should_return_null_for_null_cursor() {
    assertNull(DateTimeCursor.parse(null));
  }
}
