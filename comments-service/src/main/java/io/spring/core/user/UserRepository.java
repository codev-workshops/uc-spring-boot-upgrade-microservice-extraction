package io.spring.core.user;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository {
  Optional<User> findById(String id);
}
