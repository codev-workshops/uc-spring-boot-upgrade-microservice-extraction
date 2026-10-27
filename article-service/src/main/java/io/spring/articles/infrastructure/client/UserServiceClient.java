package io.spring.articles.infrastructure.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.spring.articles.infrastructure.client.dto.ArticleIdsResponse;
import io.spring.articles.infrastructure.client.dto.CurrentUserResponse;
import io.spring.articles.infrastructure.client.dto.FavoriteDto;
import io.spring.articles.infrastructure.client.dto.FavoritesResponse;
import io.spring.articles.infrastructure.client.dto.ProfileDto;
import io.spring.articles.infrastructure.client.dto.ProfilesResponse;
import io.spring.articles.infrastructure.client.dto.UserDto;
import io.spring.articles.infrastructure.client.dto.UserIdsResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** REST client for the read-only /internal/** endpoints exposed by the monolith. */
@Slf4j
@Component
public class UserServiceClient {
  private static final String TOKEN_PREFIX = "Token ";

  private final RestClient restClient;

  public UserServiceClient(
      RestClient.Builder builder, @Value("${user-service.base-url}") String baseUrl) {
    // The app-wide ObjectMapper unwraps root values for request bodies; the internal API
    // responses are plain JSON objects, so the client uses its own mapper.
    MappingJackson2HttpMessageConverter converter =
        new MappingJackson2HttpMessageConverter(
            JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    this.restClient =
        builder
            .baseUrl(baseUrl)
            .messageConverters(
                converters -> {
                  converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                  converters.add(0, converter);
                })
            .build();
  }

  public Optional<UserDto> findCurrentUser(String token) {
    try {
      CurrentUserResponse response =
          withToken(restClient.get().uri("/internal/me"), token)
              .retrieve()
              .body(CurrentUserResponse.class);
      return Optional.ofNullable(response).map(CurrentUserResponse::user);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
          || e.getStatusCode() == HttpStatus.FORBIDDEN) {
        return Optional.empty();
      }
      throw unavailable("authenticate the request", e);
    } catch (RestClientException e) {
      throw unavailable("authenticate the request", e);
    }
  }

  public List<String> findFollowedUserIds(String token) {
    try {
      UserIdsResponse response =
          withToken(restClient.get().uri("/internal/me/following"), token)
              .retrieve()
              .body(UserIdsResponse.class);
      return response == null || response.userIds() == null
          ? Collections.emptyList()
          : response.userIds();
    } catch (RestClientException e) {
      throw unavailable("load followed users", e);
    }
  }

  public List<ProfileDto> findProfilesByIds(Collection<String> ids, String token) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      return profiles(
          withToken(
                  restClient
                      .get()
                      .uri(b -> b.path("/internal/profiles").queryParam("ids", ids).build()),
                  token)
              .retrieve()
              .body(ProfilesResponse.class));
    } catch (RestClientException e) {
      throw unavailable("load author profiles", e);
    }
  }

  public Optional<ProfileDto> findProfileByUsername(String username, String token) {
    try {
      return profiles(
              withToken(
                      restClient
                          .get()
                          .uri(
                              b ->
                                  b.path("/internal/profiles")
                                      .queryParam("username", username)
                                      .build()),
                      token)
                  .retrieve()
                  .body(ProfilesResponse.class))
          .stream()
          .findFirst();
    } catch (RestClientException e) {
      throw unavailable("load author profile", e);
    }
  }

  /** Favorites are non-critical: on failure articles are shown with 0 / not favorited. */
  public List<FavoriteDto> findFavorites(Collection<String> articleIds, String token) {
    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      FavoritesResponse response =
          withToken(
                  restClient
                      .get()
                      .uri(
                          b ->
                              b.path("/internal/favorites")
                                  .queryParam("articleIds", articleIds)
                                  .build()),
                  token)
              .retrieve()
              .body(FavoritesResponse.class);
      return response == null || response.favorites() == null
          ? Collections.emptyList()
          : response.favorites();
    } catch (RestClientException e) {
      log.warn("User service unavailable, using default favorite data: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  public List<String> findArticleIdsFavoritedBy(String username) {
    try {
      ArticleIdsResponse response =
          restClient
              .get()
              .uri("/internal/favorites/by-user/{username}", username)
              .retrieve()
              .body(ArticleIdsResponse.class);
      return response == null || response.articleIds() == null
          ? Collections.emptyList()
          : response.articleIds();
    } catch (RestClientException e) {
      throw unavailable("load favorited articles", e);
    }
  }

  private static RestClient.RequestHeadersSpec<?> withToken(
      RestClient.RequestHeadersSpec<?> spec, String token) {
    return token == null ? spec : spec.header(HttpHeaders.AUTHORIZATION, TOKEN_PREFIX + token);
  }

  private static List<ProfileDto> profiles(ProfilesResponse response) {
    return response == null || response.profiles() == null
        ? Collections.emptyList()
        : response.profiles();
  }

  private static UserServiceException unavailable(String action, Exception cause) {
    return new UserServiceException(
        "User service unavailable: could not " + action + " (" + cause.getMessage() + ")", cause);
  }
}
