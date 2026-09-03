package io.spring.infrastructure.extraction.favorite;

/** Raised by {@link FavoriteServiceClient} for any transport or non-2xx failure. */
public class FavoriteServiceException extends RuntimeException {
  public FavoriteServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
