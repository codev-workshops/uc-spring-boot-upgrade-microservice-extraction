package io.spring.comment.api.exception;

/** Rendered as 422 {"errors":{"body":["message"]}}. */
@SuppressWarnings("serial")
public class InvalidRequestException extends RuntimeException {
  public InvalidRequestException(String message) {
    super(message);
  }
}
