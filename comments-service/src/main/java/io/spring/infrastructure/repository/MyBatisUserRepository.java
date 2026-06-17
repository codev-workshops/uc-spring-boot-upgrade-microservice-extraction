package io.spring.infrastructure.repository;

import io.spring.core.user.User;
import io.spring.core.user.UserRepository;
import io.spring.infrastructure.mybatis.mapper.UserMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MyBatisUserRepository implements UserRepository {
  private UserMapper userMapper;

  @Autowired
  public MyBatisUserRepository(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  @Override
  public Optional<User> findById(String id) {
    return Optional.ofNullable(userMapper.findById(id));
  }
}
