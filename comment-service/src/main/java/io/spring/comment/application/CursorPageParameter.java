package io.spring.comment.application;

import lombok.Getter;
import org.joda.time.DateTime;

/** Same limit clamping (default 20, max 1000) and limit+1 probe as the monolith's class. */
@Getter
public class CursorPageParameter {
  public enum Direction {
    NEXT,
    PREV
  }

  public static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 1000;

  private final int limit;
  private final DateTime cursor;
  private final Direction direction;

  public CursorPageParameter(DateTime cursor, int limit, Direction direction) {
    this.limit = limit > MAX_LIMIT ? MAX_LIMIT : (limit > 0 ? limit : DEFAULT_LIMIT);
    this.cursor = cursor;
    this.direction = direction;
  }

  public boolean isNext() {
    return direction == Direction.NEXT;
  }

  public int getQueryLimit() {
    return limit + 1;
  }
}
