package io.spring.infrastructure.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CommentServiceClient {

  private final RestClient restClient;

  public CommentServiceClient(
      @Value("${comments-service.base-url:http://localhost:8081}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public Map<String, Object> createComment(String slug, String body, String authToken) {
    return restClient
        .post()
        .uri("/articles/{slug}/comments", slug)
        .header("Authorization", "Token " + authToken)
        .header("Content-Type", "application/json")
        .body(Map.of("comment", Map.of("body", body)))
        .retrieve()
        .body(Map.class);
  }

  public Map<String, Object> getComments(String slug, String authToken) {
    RestClient.RequestHeadersSpec<?> spec = restClient.get().uri("/articles/{slug}/comments", slug);
    if (authToken != null) {
      spec = spec.header("Authorization", "Token " + authToken);
    }
    return spec.retrieve().body(Map.class);
  }

  public void deleteComment(String slug, String commentId, String authToken) {
    restClient
        .delete()
        .uri("/articles/{slug}/comments/{id}", slug, commentId)
        .header("Authorization", "Token " + authToken)
        .retrieve()
        .toBodilessEntity();
  }
}
