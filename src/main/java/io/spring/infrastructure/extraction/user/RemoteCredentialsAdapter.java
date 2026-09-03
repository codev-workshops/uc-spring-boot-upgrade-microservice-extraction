package io.spring.infrastructure.extraction.user;

import io.spring.application.user.CredentialsPort;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@code POST /internal/users/{id}/credentials/verify}; when user-service is unreachable and {@code
 * fallback=monolith} the hash still held in the local table is checked instead, otherwise the login
 * is rejected.
 */
@Component
public class RemoteCredentialsAdapter implements CredentialsPort {
  private static final Logger log = LoggerFactory.getLogger(RemoteCredentialsAdapter.class);

  private final UserServiceClient client;
  private final MyBatisUserRepository monolith;
  private final PasswordEncoder passwordEncoder;
  private final ExtractionProperties properties;

  public RemoteCredentialsAdapter(
      UserServiceClient client,
      MyBatisUserRepository monolith,
      PasswordEncoder passwordEncoder,
      ExtractionProperties properties) {
    this.client = client;
    this.monolith = monolith;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
  }

  @Override
  public boolean verify(String userId, String rawPassword) {
    try {
      return client.verifyCredentials(userId, rawPassword);
    } catch (UserServiceException e) {
      Fallback fallback = properties.getUser().getFallback();
      log.warn(
          "user-service credentials/verify failed fallback={} cause={}", fallback, e.getMessage());
      if (fallback == Fallback.MONOLITH) {
        return monolith
            .findById(userId)
            .map(user -> passwordEncoder.matches(rawPassword, user.getPassword()))
            .orElse(false);
      }
      if (fallback == Fallback.EMPTY) {
        return false;
      }
      throw e;
    }
  }
}
