package io.spring.tools.favoritesync;

/** Operator-facing failure: the message is printed as-is and the process exits with code 2. */
public class SyncException extends RuntimeException {
  public SyncException(String message) {
    super(message);
  }

  public SyncException(String message, Throwable cause) {
    super(message, cause);
  }
}
