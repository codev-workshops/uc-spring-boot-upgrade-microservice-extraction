package io.spring.infrastructure.comments;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.CursorPageParameter;
import io.spring.core.comment.CommentServiceException;
import io.spring.infrastructure.comments.CommentEnvelopes.CommentResponse;
import io.spring.infrastructure.comments.CommentEnvelopes.CommentsResponse;
import io.spring.infrastructure.comments.CommentEnvelopes.NewComment;
import io.spring.infrastructure.comments.CommentEnvelopes.NewCommentRequest;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** HTTP client for comments-service, which owns the comments data. */
@Slf4j
@Component
public class CommentServiceClient {
  static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

  private final RestTemplate restTemplate;

  public CommentServiceClient(
      RestTemplateBuilder restTemplateBuilder,
      @Value("${comments.service.url}") String baseUrl,
      @Value("${comments.service.connect-timeout:2s}") Duration connectTimeout,
      @Value("${comments.service.read-timeout:5s}") Duration readTimeout,
      @Value("${comments.service.token:}") String serviceToken) {
    if (serviceToken.isEmpty()) {
      log.warn("comments.service.token is not set; comments-service will reject requests");
    }
    // Dedicated mapper: the application's ObjectMapper unwraps root values for incoming requests.
    ObjectMapper objectMapper =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.restTemplate =
        restTemplateBuilder
            .rootUri(baseUrl)
            .messageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(readTimeout)
            .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
            .build();
  }

  public RemoteComment create(String articleId, String id, String userId, String body) {
    try {
      CommentResponse response =
          restTemplate.postForObject(
              "/articles/{articleId}/comments",
              new NewCommentRequest(new NewComment(id, body, userId)),
              CommentResponse.class,
              articleId);
      if (response == null || response.getComment() == null) {
        throw new CommentServiceException("comments-service returned an empty response", null);
      }
      return response.getComment();
    } catch (RestClientException e) {
      throw new CommentServiceException("Failed to create comment in comments-service", e);
    }
  }

  public Optional<RemoteComment> findById(String id) {
    try {
      CommentResponse response =
          restTemplate.getForObject("/comments/{id}", CommentResponse.class, id);
      return Optional.ofNullable(response).map(CommentResponse::getComment);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw new CommentServiceException(
          "Failed to fetch comment " + id + " from comments-service", e);
    }
  }

  public Optional<RemoteComment> findByArticleIdAndId(String articleId, String id) {
    try {
      CommentResponse response =
          restTemplate.getForObject(
              "/articles/{articleId}/comments/{id}", CommentResponse.class, articleId, id);
      return Optional.ofNullable(response).map(CommentResponse::getComment);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw new CommentServiceException(
          "Failed to fetch comment " + id + " from comments-service", e);
    }
  }

  public List<RemoteComment> findByArticleId(String articleId) {
    return fetchList(
        UriComponentsBuilder.fromPath("/articles/{articleId}/comments")
            .buildAndExpand(articleId)
            .toUriString(),
        articleId);
  }

  /**
   * Returns up to {@code page.getLimit() + 1} comments ordered for the page direction; the extra
   * item signals that another page exists.
   */
  public List<RemoteComment> findByArticleIdWithCursor(
      String articleId, CursorPageParameter<DateTime> page) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/articles/{articleId}/comments")
            .queryParam("limit", page.getLimit())
            .queryParam("direction", page.getDirection().name());
    if (page.getCursor() != null) {
      uri.queryParam("cursor", page.getCursor().getMillis());
    }
    return fetchList(uri.buildAndExpand(articleId).toUriString(), articleId);
  }

  public void delete(String articleId, String id) {
    try {
      restTemplate.delete("/articles/{articleId}/comments/{id}", articleId, id);
    } catch (HttpClientErrorException.NotFound e) {
      log.debug("Comment {} of article {} was already deleted", id, articleId);
    } catch (RestClientException e) {
      throw new CommentServiceException(
          "Failed to delete comment " + id + " in comments-service", e);
    }
  }

  private List<RemoteComment> fetchList(String uri, String articleId) {
    try {
      CommentsResponse response = restTemplate.getForObject(uri, CommentsResponse.class);
      return response == null || response.getComments() == null
          ? Collections.emptyList()
          : response.getComments();
    } catch (RestClientException e) {
      log.warn(
          "comments-service unavailable while listing comments of article {}: {}",
          articleId,
          e.getMessage());
      return Collections.emptyList();
    }
  }
}
