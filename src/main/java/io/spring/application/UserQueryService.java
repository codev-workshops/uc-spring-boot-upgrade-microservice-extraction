package io.spring.application;

import io.spring.application.data.UserData;
import io.spring.application.user.UserQueryPort;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserQueryService {
  private final UserReadService userReadService;
  private final UserQueryPort userQueryPort;

  @Autowired
  public UserQueryService(
      UserReadService userReadService, ObjectProvider<UserQueryPort> userQueryPort) {
    this(userReadService, userQueryPort.getIfAvailable());
  }

  public UserQueryService(UserReadService userReadService) {
    this(userReadService, (UserQueryPort) null);
  }

  public UserQueryService(UserReadService userReadService, UserQueryPort userQueryPort) {
    this.userReadService = userReadService;
    this.userQueryPort = userQueryPort;
  }

  public Optional<UserData> findById(String id) {
    if (userQueryPort != null && userQueryPort.ownsUserReads()) {
      return userQueryPort.findById(id);
    }
    return Optional.ofNullable(userReadService.findById(id));
  }
}
