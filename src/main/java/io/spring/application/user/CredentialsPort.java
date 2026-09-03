package io.spring.application.user;

/**
 * Verifies a raw password for a user whose hash is not available in-process (rows loaded from
 * user-service carry no hash).
 */
public interface CredentialsPort {
  boolean verify(String userId, String rawPassword);
}
