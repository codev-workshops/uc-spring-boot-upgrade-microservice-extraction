package io.spring.infrastructure.extraction.user;

import io.spring.application.data.UserData;
import io.spring.application.user.UserQueryPort;
import io.spring.application.user.dto.UserRowDto;
import io.spring.core.user.User;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * user-service implementation of {@link UserQueryPort}; failures surface as {@link
 * UserServiceException}.
 */
@Component
public class RemoteUserQueryAdapter implements UserQueryPort {
  private final UserServiceClient client;

  public RemoteUserQueryAdapter(UserServiceClient client) {
    this.client = client;
  }

  @Override
  public Optional<UserData> findById(String id) {
    return client.findById(id).map(RemoteUserQueryAdapter::toData);
  }

  @Override
  public Optional<UserData> findByUsername(String username) {
    return client.findByUsername(username).map(RemoteUserQueryAdapter::toData);
  }

  @Override
  public Optional<UserData> findByEmail(String email) {
    return client.findByEmail(email).map(RemoteUserQueryAdapter::toData);
  }

  @Override
  public List<UserData> findByIds(List<String> ids) {
    return client.findByIds(ids).stream()
        .map(RemoteUserQueryAdapter::toData)
        .collect(Collectors.toList());
  }

  static UserData toData(UserRowDto row) {
    return new UserData(
        row.getId(), row.getEmail(), row.getUsername(), row.getBio(), row.getImage());
  }

  /**
   * Core {@link User} for a remote row. The password is blank (user-service never returns the
   * hash): {@code User.update}/{@code UserMapper.xml#update} skip blank fields, and {@code
   * LoginService} switches to {@code credentials/verify}.
   */
  static User toUser(UserRowDto row) {
    return new User(
        row.getId(), row.getEmail(), row.getUsername(), "", row.getBio(), row.getImage());
  }
}
