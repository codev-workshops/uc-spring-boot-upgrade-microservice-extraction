package io.spring.user.infrastructure.repository;

import io.spring.user.core.user.FollowRelation;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserRepository;
import io.spring.user.core.user.UserUpdate;
import io.spring.user.infrastructure.mybatis.mapper.UserMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisUserRepository implements UserRepository {
  private final UserMapper mapper;

  public MyBatisUserRepository(UserMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(User user) {
    mapper.insert(user);
  }

  @Override
  public void update(UserUpdate update) {
    mapper.update(update);
  }

  @Override
  public Optional<User> findById(String id) {
    return Optional.ofNullable(mapper.findById(id));
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return Optional.ofNullable(mapper.findByUsername(username));
  }

  @Override
  public Optional<User> findByEmail(String email) {
    return Optional.ofNullable(mapper.findByEmail(email));
  }

  @Override
  public List<User> findByIds(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    return mapper.findByIds(ids);
  }

  @Override
  public Optional<FollowRelation> findRelation(String userId, String targetId) {
    return Optional.ofNullable(mapper.findRelation(userId, targetId));
  }

  @Override
  public void saveRelation(FollowRelation relation) {
    mapper.saveRelation(relation);
  }

  @Override
  public void removeRelation(FollowRelation relation) {
    mapper.deleteRelation(relation);
  }

  @Override
  public List<String> followingAuthors(String userId, List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    return mapper.followingAuthors(userId, ids);
  }

  @Override
  public List<String> followedUsers(String userId) {
    return mapper.followedUsers(userId);
  }
}
