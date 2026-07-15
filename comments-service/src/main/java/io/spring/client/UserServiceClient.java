package io.spring.client;

import io.spring.client.dto.BatchProfileRequest;
import io.spring.client.dto.ProfileResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class UserServiceClient {
  private final RestTemplate restTemplate;
  private final String monolithBaseUrl;
  private final String internalServiceKey;

  public UserServiceClient(
      RestTemplate restTemplate,
      @Value("${monolith.base-url}") String monolithBaseUrl,
      @Value("${jwt.secret}") String internalServiceKey) {
    this.restTemplate = restTemplate;
    this.monolithBaseUrl = monolithBaseUrl;
    this.internalServiceKey = internalServiceKey;
  }

  public List<ProfileResponse> findProfiles(String viewerId, List<String> userIds) {
    if (userIds.isEmpty()) {
      return Collections.emptyList();
    }
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Internal-Service-Key", internalServiceKey);
    ProfileResponse[] profiles =
        restTemplate.postForObject(
            monolithBaseUrl + "/internal/profiles/batch",
            new HttpEntity<>(new BatchProfileRequest(viewerId, userIds), headers),
            ProfileResponse[].class);
    return profiles == null ? Collections.emptyList() : Arrays.asList(profiles);
  }
}
