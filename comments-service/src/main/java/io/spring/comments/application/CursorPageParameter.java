package io.spring.comments.application;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

@Data
@NoArgsConstructor
public class CursorPageParameter {
  private static final int MAX_LIMIT = 1000;
  private int limit = 20;
  private DateTime cursor;
  private Direction direction = Direction.NEXT;

  public CursorPageParameter(DateTime cursor, int limit, Direction direction) {
    setLimit(limit);
    this.cursor = cursor;
    if (direction != null) {
      this.direction = direction;
    }
  }

  public int getQueryLimit() {
    return limit + 1;
  }

  private void setLimit(int limit) {
    if (limit > MAX_LIMIT) {
      this.limit = MAX_LIMIT;
    } else if (limit > 0) {
      this.limit = limit;
    }
  }
}
