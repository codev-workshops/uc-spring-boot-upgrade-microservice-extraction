package io.spring.client;

import io.spring.api.exception.ArticleServiceException;
import io.spring.client.dto.ArticleResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class ArticleServiceClient {
  private final RestTemplate restTemplate;
  private final String monolithBaseUrl;
  private final String internalServiceKey;
  private final Map<String, ArticleResponse> cache = new ConcurrentHashMap<>();

  public ArticleServiceClient(
      RestTemplate restTemplate,
      @Value("${monolith.base-url}") String monolithBaseUrl,
      @Value("${jwt.secret}") String internalServiceKey) {
    this.restTemplate = restTemplate;
    this.monolithBaseUrl = monolithBaseUrl;
    this.internalServiceKey = internalServiceKey;
  }

  public Optional<ArticleResponse> findBySlug(String slug) {
    return requestArticle(slug);
  }

  public Optional<ArticleResponse> findBySlugForRead(String slug) {
    try {
      return requestArticle(slug);
    } catch (ArticleServiceException e) {
      ArticleResponse cachedArticle = cache.get(slug);
      if (cachedArticle != null) {
        return Optional.of(cachedArticle);
      }
      throw e;
    }
  }

  private Optional<ArticleResponse> requestArticle(String slug) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Internal-Service-Key", internalServiceKey);
    try {
      ResponseEntity<ArticleResponse> response =
          restTemplate.exchange(
              monolithBaseUrl + "/internal/articles/" + slug,
              HttpMethod.GET,
              new HttpEntity<>(headers),
              ArticleResponse.class);
      ArticleResponse article = response.getBody();
      if (article != null) {
        cache.put(slug, article);
      }
      return Optional.ofNullable(article);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw new ArticleServiceException(
          "Unable to resolve article '" + slug + "' from the monolith", e);
    }
  }
}
