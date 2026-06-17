package io.spring.infrastructure.client;

import io.spring.api.exception.ResourceNotFoundException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class ArticleServiceClient {

  private final RestClient restClient;

  public ArticleServiceClient(@Value("${monolith.base-url}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public ArticleDTO getArticleBySlug(String slug) {
    try {
      Map<String, Object> response =
          restClient.get().uri("/articles/{slug}", slug).retrieve().body(Map.class);
      if (response == null || !response.containsKey("article")) {
        throw new ResourceNotFoundException();
      }
      Map<String, Object> article = (Map<String, Object>) response.get("article");
      ArticleDTO dto = new ArticleDTO();
      dto.setId((String) article.get("id"));
      dto.setSlug((String) article.get("slug"));
      dto.setUserId((String) article.get("userId"));
      return dto;
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResourceNotFoundException();
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch article by slug: " + slug, e);
    }
  }
}
