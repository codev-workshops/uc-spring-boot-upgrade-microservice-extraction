package io.spring.infrastructure.client;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UserServiceClient {

  private final RestClient restClient;

  public UserServiceClient(@Value("${monolith.base-url}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public boolean isUserFollowing(String userId, String anotherUserId) {
    // Stub: the monolith doesn't expose a direct follow-check endpoint yet.
    // Enhance when the monolith adds an internal API.
    return false;
  }

  public Set<String> followingAuthors(String userId, List<String> ids) {
    // Stub: the monolith doesn't expose a bulk follow-check endpoint yet.
    // Enhance when the monolith adds an internal API.
    return Collections.emptySet();
  }
}
