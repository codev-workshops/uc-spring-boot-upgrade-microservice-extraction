package io.spring.favorite.api.exception;

/** Rendered as 401 {"errors":{"body":["message"]}}. */
@SuppressWarnings("serial")
public class InvalidAuthenticationException extends RuntimeException {
  public InvalidAuthenticationException() {
    super("missing or invalid token");
  }
}
