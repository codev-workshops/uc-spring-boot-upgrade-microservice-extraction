package io.spring.infrastructure.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UserServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(UserServiceClient.class);

  private final RestClient restClient;

  public UserServiceClient(@Value("${monolith.base-url}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public boolean isUserFollowing(String userId, String anotherUserId) {
    try {
      // For now, return false as the monolith doesn't expose a direct follow-check endpoint.
      // This can be enhanced when the monolith adds an internal API.
      return false;
    } catch (Exception e) {
      logger.warn("Failed to check user following status: {}", e.getMessage());
      return false;
    }
  }

  public Set<String> followingAuthors(String userId, List<String> ids) {
    try {
      // Return empty set as the monolith doesn't expose a bulk follow-check endpoint.
      // This can be enhanced when the monolith adds an internal API.
      return Collections.emptySet();
    } catch (Exception e) {
      logger.warn("Failed to fetch following authors: {}", e.getMessage());
      return new HashSet<>();
    }
  }
}
