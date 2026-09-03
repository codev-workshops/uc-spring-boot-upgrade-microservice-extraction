package io.spring.infrastructure.extraction.user;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.user.dto.CredentialsVerifiedDto;
import io.spring.application.user.dto.FollowedIdsDto;
import io.spring.application.user.dto.FollowingDto;
import io.spring.application.user.dto.FollowingIdsDto;
import io.spring.application.user.dto.NewUserRequest;
import io.spring.application.user.dto.UpdateUserRequest;
import io.spring.application.user.dto.UserDto;
import io.spring.application.user.dto.UserRowDto;
import io.spring.application.user.dto.UsersDto;
import io.spring.application.user.dto.VerifyCredentialsRequest;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for user-service's internal API (docs/microservice-extraction/phases/phase-5-user.md,
 * section 2.1). Reads are sent without credentials and retried once on connect failure / 503;
 * writes forward the caller's {@code Authorization} header and are never retried. Password hashes
 * travel only in request bodies ({@code passwordHash}) and are never logged. Every failure surfaces
 * as {@link UserServiceException}. Configured by {@code extraction.user.*}.
 */
@Component
public class UserServiceClient {
  private static final Logger log = LoggerFactory.getLogger(UserServiceClient.class);

  private final RestTemplate rest;
  private final ExtractionProperties properties;
  private final AuthTokenPropagator auth;

  public UserServiceClient(
      RestTemplateBuilder builder, ExtractionProperties properties, AuthTokenPropagator auth) {
    DomainRoute route = properties.getUser();
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

  /** {@code GET /internal/users/{id}}; a 404 is an empty result. */
  public Optional<UserRowDto> findById(String id) {
    return single(url("/internal/users/{id}"), id);
  }

  /** {@code GET /internal/users/by-username/{username}}; a 404 is an empty result. */
  public Optional<UserRowDto> findByUsername(String username) {
    return single(url("/internal/users/by-username/{username}"), username);
  }

  /** {@code GET /internal/users/by-email/{email}}; a 404 is an empty result. */
  public Optional<UserRowDto> findByEmail(String email) {
    return single(url("/internal/users/by-email/{email}"), email);
  }

  /** {@code GET /internal/users?ids=a,b}; an empty batch is not sent. */
  public List<UserRowDto> findByIds(List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }
    String url = url("/internal/users?ids=" + String.join(",", ids));
    UsersDto body = read("GET " + url, () -> rest.getForObject(url, UsersDto.class));
    return body == null || body.getUsers() == null ? Collections.emptyList() : body.getUsers();
  }

  /** {@code POST /internal/users} (idempotent on {@code id}; ships the BCrypt hash). */
  public UserRowDto create(User user) {
    String url = url("/internal/users");
    NewUserRequest request =
        new NewUserRequest(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getPassword(),
            user.getBio(),
            user.getImage());
    UserDto body =
        write(
            "POST " + url,
            () ->
                rest.postForObject(
                    url, new HttpEntity<>(request, jsonHeaders(true)), UserDto.class));
    return body == null ? null : body.getUser();
  }

  /** {@code PUT /internal/users/{id}}; blank fields (including a blank hash) are skipped. */
  public UserRowDto update(User user) {
    String url = url("/internal/users/" + user.getId());
    UpdateUserRequest request =
        new UpdateUserRequest(
            user.getUsername(),
            user.getEmail(),
            user.getPassword(),
            user.getBio(),
            user.getImage());
    UserDto body =
        write(
            "PUT " + url,
            () ->
                rest.exchange(
                        url,
                        HttpMethod.PUT,
                        new HttpEntity<>(request, jsonHeaders(true)),
                        UserDto.class)
                    .getBody());
    return body == null ? null : body.getUser();
  }

  /**
   * {@code POST /internal/users/{id}/credentials/verify}. Not retried (it carries a secret) and
   * sent without the caller's token (the caller is not authenticated yet); an unknown user is
   * {@code false}.
   */
  public boolean verifyCredentials(String id, String rawPassword) {
    String url = url("/internal/users/" + id + "/credentials/verify");
    try {
      CredentialsVerifiedDto body =
          write(
              "POST " + url,
              () ->
                  rest.postForObject(
                      url,
                      new HttpEntity<>(
                          new VerifyCredentialsRequest(rawPassword), jsonHeaders(false)),
                      CredentialsVerifiedDto.class));
      return body != null && body.isValid();
    } catch (UserServiceException e) {
      if (e.getCause() instanceof HttpClientErrorException.NotFound) {
        return false;
      }
      throw e;
    }
  }

  /** {@code GET /internal/users/{id}/following?ids=a,b}; an empty batch is not sent. */
  public List<String> followingIds(String userId, List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }
    String url = url("/internal/users/" + userId + "/following?ids=" + String.join(",", ids));
    FollowingIdsDto body = read("GET " + url, () -> rest.getForObject(url, FollowingIdsDto.class));
    return body == null || body.getFollowingIds() == null
        ? Collections.emptyList()
        : body.getFollowingIds();
  }

  /** {@code GET /internal/users/{id}/followed}. */
  public List<String> followedIds(String userId) {
    String url = url("/internal/users/" + userId + "/followed");
    FollowedIdsDto body = read("GET " + url, () -> rest.getForObject(url, FollowedIdsDto.class));
    return body == null || body.getFollowedIds() == null
        ? Collections.emptyList()
        : body.getFollowedIds();
  }

  /** {@code GET /internal/users/{id}/follows/{targetId}}. */
  public boolean isFollowing(String userId, String targetId) {
    String url = url("/internal/users/" + userId + "/follows/" + targetId);
    FollowingDto body = read("GET " + url, () -> rest.getForObject(url, FollowingDto.class));
    return body != null && body.isFollowing();
  }

  /** {@code PUT /internal/users/{id}/follows/{targetId}} (idempotent). */
  public void follow(String userId, String targetId) {
    String url = url("/internal/users/" + userId + "/follows/" + targetId);
    write(
        "PUT " + url,
        () -> rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(jsonHeaders(true)), Void.class));
  }

  /** {@code DELETE /internal/users/{id}/follows/{targetId}} (idempotent, 204). */
  public void unfollow(String userId, String targetId) {
    String url = url("/internal/users/" + userId + "/follows/" + targetId);
    write(
        "DELETE " + url,
        () ->
            rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(true)), Void.class));
  }

  private Optional<UserRowDto> single(String url, String variable) {
    try {
      UserDto body = read("GET " + url, () -> rest.getForObject(url, UserDto.class, variable));
      return body == null ? Optional.empty() : Optional.ofNullable(body.getUser());
    } catch (UserServiceException e) {
      if (e.getCause() instanceof HttpClientErrorException.NotFound) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private String url(String path) {
    String base = properties.getUser().getBaseUrl().toString();
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
      log.debug("user-service {} failed to connect, retrying once", call);
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

  private static UserServiceException wrap(String call, RestClientException e) {
    String detail =
        e instanceof HttpStatusCodeException
            ? "status " + ((HttpStatusCodeException) e).getRawStatusCode()
            : e.getClass().getSimpleName();
    return new UserServiceException("user-service call failed: " + call + " (" + detail + ")", e);
  }
}
