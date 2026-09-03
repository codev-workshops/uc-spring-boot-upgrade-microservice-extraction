package io.spring.user.api.exception;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The monolith error envelope: {"errors":{"<field>":["..."]}}. */
public final class ErrorResource {
  private ErrorResource() {}

  public static Map<String, Map<String, List<String>>> body(String message) {
    return body("body", message);
  }

  public static Map<String, Map<String, List<String>>> body(String field, String message) {
    return Collections.singletonMap(
        "errors", Collections.singletonMap(field, Collections.singletonList(message)));
  }
}
