package io.spring.articles.infrastructure.client;

/** The monolith (user service) could not be reached or returned an unexpected error. */
public class UserServiceException extends RuntimeException {
  public UserServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
