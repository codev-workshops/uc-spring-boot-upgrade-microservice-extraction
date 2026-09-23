package io.spring.api;

import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.DateTimeCursor;
import io.spring.application.Node;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joda.time.DateTime;

/**
 * Helpers for exposing {@link CursorPager} results over REST using Relay-style query parameters
 * ({@code first}/{@code after} for the next page, {@code last}/{@code before} for the previous).
 */
final class CursorPageResponse {

  private CursorPageResponse() {}

  static CursorPageParameter<DateTime> pageParameter(
      Integer first, String after, Integer last, String before) {
    if (first != null) {
      return new CursorPageParameter<>(DateTimeCursor.parse(after), first, Direction.NEXT);
    }
    if (last != null) {
      return new CursorPageParameter<>(DateTimeCursor.parse(before), last, Direction.PREV);
    }
    throw new IllegalArgumentException("exactly one of first or last must be provided");
  }

  static <T extends Node> Map<String, Object> of(String dataKey, CursorPager<T> pager) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(dataKey, pager.getData());
    body.put("startCursor", cursorToString(pager.getStartCursor()));
    body.put("endCursor", cursorToString(pager.getEndCursor()));
    body.put("hasNext", pager.hasNext());
    body.put("hasPrevious", pager.hasPrevious());
    return body;
  }

  private static String cursorToString(Object cursor) {
    return cursor == null ? null : cursor.toString();
  }
}
