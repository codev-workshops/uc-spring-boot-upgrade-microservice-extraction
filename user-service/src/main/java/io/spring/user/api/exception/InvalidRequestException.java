package io.spring.user.api.exception;

/** Rendered as 422 {"errors":{"<field>":["message"]}}. */
@SuppressWarnings("serial")
public class InvalidRequestException extends RuntimeException {
  private final String field;

  public InvalidRequestException(String message) {
    this("body", message);
  }

  public InvalidRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
