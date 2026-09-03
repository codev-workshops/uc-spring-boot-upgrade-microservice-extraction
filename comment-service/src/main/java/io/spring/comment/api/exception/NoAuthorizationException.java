package io.spring.comment.api.exception;

/** Rendered as 403 {"errors":{"body":["message"]}}. */
@SuppressWarnings("serial")
public class NoAuthorizationException extends RuntimeException {
  public NoAuthorizationException() {
    super("token subject does not match userId");
  }
}
