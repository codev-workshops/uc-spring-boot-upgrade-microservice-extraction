package io.spring.infrastructure.extraction.user;

import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import org.springframework.stereotype.Component;

/** Writes against user-service; failures surface as {@link UserServiceException}. */
@Component
public class RemoteUserCommand implements UserCommandPort {
  private final UserServiceClient client;

  public RemoteUserCommand(UserServiceClient client) {
    this.client = client;
  }

  @Override
  public void create(User user) {
    client.create(user);
  }

  @Override
  public void update(User user) {
    client.update(user);
  }

  @Override
  public void follow(FollowRelation relation) {
    client.follow(relation.getUserId(), relation.getTargetId());
  }

  @Override
  public void unfollow(FollowRelation relation) {
    client.unfollow(relation.getUserId(), relation.getTargetId());
  }
}
