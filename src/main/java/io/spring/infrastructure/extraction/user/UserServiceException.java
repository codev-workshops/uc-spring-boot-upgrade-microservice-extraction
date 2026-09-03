package io.spring.infrastructure.extraction.user;

/** Transport or non-2xx failure while calling user-service. */
public class UserServiceException extends RuntimeException {
  public UserServiceException(String message) {
    super(message);
  }

  public UserServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
