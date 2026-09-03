package io.spring.user.application;

import io.spring.user.application.data.UserData;
import io.spring.user.core.user.UserRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class UserQueryService {
  private final UserRepository userRepository;

  public UserQueryService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public Optional<UserData> findById(String id) {
    return userRepository.findById(id).map(UserData::of);
  }

  public Optional<UserData> findByUsername(String username) {
    return userRepository.findByUsername(username).map(UserData::of);
  }

  public Optional<UserData> findByEmail(String email) {
    return userRepository.findByEmail(email).map(UserData::of);
  }

  public List<UserData> findByIds(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    return userRepository.findByIds(ids).stream().map(UserData::of).collect(Collectors.toList());
  }

  public boolean isFollowing(String userId, String targetId) {
    return userRepository.findRelation(userId, targetId).isPresent();
  }

  public List<String> followingAuthors(String userId, List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    return userRepository.followingAuthors(userId, ids);
  }

  public List<String> followedUsers(String userId) {
    return userRepository.followedUsers(userId);
  }
}
