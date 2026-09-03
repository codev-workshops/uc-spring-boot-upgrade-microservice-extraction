package io.spring.user.application;

import io.spring.user.api.exception.InvalidRequestException;
import io.spring.user.api.exception.ResourceNotFoundException;
import io.spring.user.application.data.UserData;
import io.spring.user.core.user.FollowRelation;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserRepository;
import io.spring.user.core.user.UserUpdate;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Writes for the users/follows tables. Password hashing stays in the monolith: callers supply the
 * BCrypt hash, which is stored verbatim and only ever compared here (verifyCredentials).
 */
@Service
public class UserCommandService {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserCommandService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /** Outcome of an idempotent create: the stored row plus whether this call inserted it. */
  public static final class CreateResult {
    private final UserData user;
    private final boolean created;

    public CreateResult(UserData user, boolean created) {
      this.user = user;
      this.created = created;
    }

    public UserData getUser() {
      return user;
    }

    public boolean isCreated() {
      return created;
    }
  }

  /**
   * Idempotent by id: an existing id returns the stored row unchanged. A username or email held by
   * a different id is rejected with the monolith's "duplicated username"/"duplicated email".
   */
  public CreateResult create(User user) {
    Optional<User> existing = userRepository.findById(user.getId());
    if (existing.isPresent()) {
      return new CreateResult(UserData.of(existing.get()), false);
    }
    checkUnique(user.getId(), user.getUsername(), user.getEmail());
    userRepository.save(user);
    return new CreateResult(UserData.of(userRepository.findById(user.getId()).orElse(user)), true);
  }

  /** Blank/null fields are skipped exactly like the monolith's UserMapper.xml#update. */
  public UserData update(UserUpdate update) {
    userRepository.findById(update.getId()).orElseThrow(ResourceNotFoundException::new);
    checkUnique(update.getId(), update.getUsername(), update.getEmail());
    userRepository.update(update);
    return UserData.of(
        userRepository.findById(update.getId()).orElseThrow(ResourceNotFoundException::new));
  }

  /** Unknown id or null password is simply false; never logs anything. */
  public boolean verifyCredentials(String id, String password) {
    if (password == null) {
      return false;
    }
    return userRepository
        .findById(id)
        .map(
            user ->
                user.getPassword() != null && passwordEncoder.matches(password, user.getPassword()))
        .orElse(false);
  }

  public void follow(String userId, String targetId) {
    userRepository.saveRelation(new FollowRelation(userId, targetId));
  }

  public void unfollow(String userId, String targetId) {
    userRepository.removeRelation(new FollowRelation(userId, targetId));
  }

  private void checkUnique(String id, String username, String email) {
    if (username != null && !username.isEmpty()) {
      Optional<User> byUsername = userRepository.findByUsername(username);
      if (byUsername.isPresent() && !byUsername.get().getId().equals(id)) {
        throw new InvalidRequestException("username", "duplicated username");
      }
    }
    if (email != null && !email.isEmpty()) {
      Optional<User> byEmail = userRepository.findByEmail(email);
      if (byEmail.isPresent() && !byEmail.get().getId().equals(id)) {
        throw new InvalidRequestException("email", "duplicated email");
      }
    }
  }
}
