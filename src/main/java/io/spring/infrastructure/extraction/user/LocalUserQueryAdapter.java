package io.spring.infrastructure.extraction.user;

import io.spring.application.data.UserData;
import io.spring.application.user.UserQueryPort;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Monolith SQL implementation of {@link UserQueryPort} over {@link UserReadService}. */
@Component
public class LocalUserQueryAdapter implements UserQueryPort {
  private final UserReadService users;

  public LocalUserQueryAdapter(UserReadService users) {
    this.users = users;
  }

  @Override
  public Optional<UserData> findById(String id) {
    return Optional.ofNullable(users.findById(id));
  }

  @Override
  public Optional<UserData> findByUsername(String username) {
    return Optional.ofNullable(users.findByUsername(username));
  }

  @Override
  public Optional<UserData> findByEmail(String email) {
    return Optional.ofNullable(users.findByEmail(email));
  }

  @Override
  public List<UserData> findByIds(List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }
    return users.findByIds(ids);
  }
}
