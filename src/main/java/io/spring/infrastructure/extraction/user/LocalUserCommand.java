package io.spring.infrastructure.extraction.user;

import io.spring.application.user.UserCommandPort;
import io.spring.core.user.FollowRelation;
import io.spring.core.user.User;
import io.spring.infrastructure.repository.MyBatisUserRepository;
import org.springframework.stereotype.Component;

/** Monolith writes through the original {@link MyBatisUserRepository}. */
@Component
public class LocalUserCommand implements UserCommandPort {
  private final MyBatisUserRepository repository;

  public LocalUserCommand(MyBatisUserRepository repository) {
    this.repository = repository;
  }

  @Override
  public void create(User user) {
    repository.save(user);
  }

  @Override
  public void update(User user) {
    repository.save(user);
  }

  @Override
  public void follow(FollowRelation relation) {
    repository.saveRelation(relation);
  }

  @Override
  public void unfollow(FollowRelation relation) {
    repository.removeRelation(relation);
  }
}
