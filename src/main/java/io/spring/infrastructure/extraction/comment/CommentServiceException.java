package io.spring.infrastructure.extraction.comment;

/** Raised by {@link CommentServiceClient} for any transport or non-2xx failure. */
public class CommentServiceException extends RuntimeException {
  public CommentServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
