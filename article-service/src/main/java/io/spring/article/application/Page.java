package io.spring.article.application;

import lombok.Getter;

/** Same clamping as the monolith's io.spring.application.Page (offset >= 0, limit 1..100). */
@Getter
public class Page {
  private static final int MAX_LIMIT = 100;
  private int offset = 0;
  private int limit = 20;

  public Page(int offset, int limit) {
    if (offset > 0) {
      this.offset = offset;
    }
    if (limit > MAX_LIMIT) {
      this.limit = MAX_LIMIT;
    } else if (limit > 0) {
      this.limit = limit;
    }
  }
}
