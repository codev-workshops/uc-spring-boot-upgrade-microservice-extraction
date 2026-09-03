package io.spring.application.user;

import io.spring.Util;
import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Email/password login. Rows read from the local table carry the BCrypt hash and are checked
 * in-process exactly as before; rows read from user-service carry no hash and are checked through
 * {@link CredentialsPort} ({@code POST /internal/users/{id}/credentials/verify}).
 */
@Service
public class LoginService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final CredentialsPort credentials;

  @Autowired
  public LoginService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      ObjectProvider<CredentialsPort> credentials) {
    this(userRepository, passwordEncoder, credentials.getIfAvailable());
  }

  public LoginService(
      UserRepository userRepository, PasswordEncoder passwordEncoder, CredentialsPort credentials) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.credentials = credentials;
  }

  public Optional<User> login(String email, String rawPassword) {
    Optional<User> optional = userRepository.findByEmail(email);
    if (!optional.isPresent()) {
      return Optional.empty();
    }
    User user = optional.get();
    boolean valid;
    if (!Util.isEmpty(user.getPassword())) {
      valid = passwordEncoder.matches(rawPassword, user.getPassword());
    } else {
      valid = credentials != null && credentials.verify(user.getId(), rawPassword);
    }
    return valid ? optional : Optional.empty();
  }
}
