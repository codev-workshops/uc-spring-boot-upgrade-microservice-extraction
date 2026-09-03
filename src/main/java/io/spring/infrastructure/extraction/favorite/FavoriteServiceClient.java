package io.spring.infrastructure.extraction.favorite;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.favorite.dto.ArticleIdsRequest;
import io.spring.application.favorite.dto.FavoriteCountDto;
import io.spring.application.favorite.dto.FavoriteCountsDto;
import io.spring.application.favorite.dto.FavoriteDto;
import io.spring.application.favorite.dto.UserFavoritesDto;
import io.spring.application.favorite.dto.UserFavoritesQueryRequest;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the internal API of favorite-service (see
 * docs/microservice-extraction/phases/phase-1-favorite.md, section 2.1). Reads are sent without
 * credentials and retried once on connect failure / 503; writes forward the caller's {@code
 * Authorization} header and are never retried. Every failure surfaces as {@link
 * FavoriteServiceException}.
 */
@Component
public class FavoriteServiceClient {
  private static final Logger log = LoggerFactory.getLogger(FavoriteServiceClient.class);

  private final RestTemplate rest;
  private final ExtractionProperties properties;
  private final AuthTokenPropagator auth;

  public FavoriteServiceClient(
      RestTemplateBuilder builder, ExtractionProperties properties, AuthTokenPropagator auth) {
    DomainRoute route = properties.getFavorite();
    ObjectMapper mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.rest =
        builder
            .setConnectTimeout(route.getConnectTimeout())
            .setReadTimeout(route.getReadTimeout())
            .messageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    this.properties = properties;
    this.auth = auth;
  }

  /** Exposed so tests can bind a {@code MockRestServiceServer}. */
  public RestTemplate getRestTemplate() {
    return rest;
  }

  /** {@code POST /internal/favorites/counts}. */
  public List<FavoriteCountDto> counts(List<String> articleIds) {
    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    String url = url("/internal/favorites/counts");
    FavoriteCountsDto body =
        read(
            "POST " + url,
            () ->
                rest.postForObject(
                    url,
                    new HttpEntity<>(new ArticleIdsRequest(articleIds), jsonHeaders(false)),
                    FavoriteCountsDto.class));
    return body == null || body.getCounts() == null ? Collections.emptyList() : body.getCounts();
  }

  /** {@code POST /internal/favorites/query}. */
  public UserFavoritesDto userFavorites(String userId, List<String> articleIds) {
    if (articleIds.isEmpty()) {
      return new UserFavoritesDto(userId, new ArrayList<>());
    }
    String url = url("/internal/favorites/query");
    UserFavoritesDto body =
        read(
            "POST " + url,
            () ->
                rest.postForObject(
                    url,
                    new HttpEntity<>(
                        new UserFavoritesQueryRequest(userId, articleIds), jsonHeaders(false)),
                    UserFavoritesDto.class));
    return body == null ? new UserFavoritesDto(userId, new ArrayList<>()) : body;
  }

  /** {@code GET /internal/favorites/by-user/{userId}/article-ids}. */
  public UserFavoritesDto articleIdsFavoritedBy(String userId) {
    String url = url("/internal/favorites/by-user/" + userId + "/article-ids");
    UserFavoritesDto body =
        read("GET " + url, () -> rest.getForObject(url, UserFavoritesDto.class));
    return body == null ? new UserFavoritesDto(userId, new ArrayList<>()) : body;
  }

  /** {@code PUT /internal/favorites/{articleId}/{userId}} (idempotent). */
  public FavoriteDto favorite(String articleId, String userId) {
    String url = url("/internal/favorites/" + articleId + "/" + userId);
    ResponseEntity<FavoriteDto> response =
        write(
            "PUT " + url,
            () ->
                rest.exchange(
                    url, HttpMethod.PUT, new HttpEntity<>(jsonHeaders(true)), FavoriteDto.class));
    return response.getBody();
  }

  /** {@code DELETE /internal/favorites/{articleId}/{userId}} (idempotent, 204). */
  public void unfavorite(String articleId, String userId) {
    String url = url("/internal/favorites/" + articleId + "/" + userId);
    write(
        "DELETE " + url,
        () ->
            rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(true)), Void.class));
  }

  private String url(String path) {
    String base = properties.getFavorite().getBaseUrl().toString();
    return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
  }

  private HttpHeaders jsonHeaders(boolean propagateAuth) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    if (propagateAuth) {
      auth.currentAuthorization().ifPresent(token -> headers.set(HttpHeaders.AUTHORIZATION, token));
    }
    return headers;
  }

  private <T> T read(String call, Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientException first) {
      if (!retryable(first)) {
        throw wrap(call, first);
      }
      log.debug("favorite-service {} failed to connect, retrying once", call);
      try {
        return request.get();
      } catch (RestClientException second) {
        throw wrap(call, second);
      }
    }
  }

  private <T> T write(String call, Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientException e) {
      throw wrap(call, e);
    }
  }

  static boolean retryable(RestClientException e) {
    if (e instanceof HttpStatusCodeException) {
      return ((HttpStatusCodeException) e).getStatusCode().value() == 503;
    }
    if (e instanceof ResourceAccessException) {
      Throwable cause = e.getCause();
      if (cause instanceof ConnectException) {
        return true;
      }
      return cause instanceof SocketTimeoutException
          && cause.getMessage() != null
          && cause.getMessage().toLowerCase().contains("connect");
    }
    return false;
  }

  private static FavoriteServiceException wrap(String call, RestClientException e) {
    String detail =
        e instanceof HttpStatusCodeException
            ? "status " + ((HttpStatusCodeException) e).getRawStatusCode()
            : e.getClass().getSimpleName();
    return new FavoriteServiceException(
        "favorite-service call failed: " + call + " (" + detail + ")", e);
  }
}
