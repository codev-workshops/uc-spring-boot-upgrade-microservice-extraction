package io.spring.comment.api.exception;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The monolith error envelope: {"errors":{"body":["..."]}}. */
public final class ErrorResource {
  private ErrorResource() {}

  public static Map<String, Map<String, List<String>>> body(String message) {
    return Collections.singletonMap(
        "errors", Collections.singletonMap("body", Collections.singletonList(message)));
  }
}
