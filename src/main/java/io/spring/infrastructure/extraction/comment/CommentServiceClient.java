package io.spring.infrastructure.extraction.comment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.CursorPageParameter;
import io.spring.application.comment.dto.CommentDto;
import io.spring.application.comment.dto.CommentRowDto;
import io.spring.application.comment.dto.CommentsDto;
import io.spring.application.comment.dto.NewCommentRequest;
import io.spring.core.comment.Comment;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
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
 * HTTP client for the internal API of comment-service (see
 * docs/microservice-extraction/phases/phase-2-comment.md, section 2.1). Reads are sent without
 * credentials and retried once on connect failure / 503; writes forward the caller's {@code
 * Authorization} header and are never retried. Every failure surfaces as {@link
 * CommentServiceException}.
 */
@Component
public class CommentServiceClient {
  private static final Logger log = LoggerFactory.getLogger(CommentServiceClient.class);

  private final RestTemplate rest;
  private final ExtractionProperties properties;
  private final AuthTokenPropagator auth;

  public CommentServiceClient(
      RestTemplateBuilder builder, ExtractionProperties properties, AuthTokenPropagator auth) {
    DomainRoute route = properties.getComment();
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

  /** {@code GET /internal/articles/{articleId}/comments}. */
  public List<CommentRowDto> findByArticleId(String articleId) {
    String url = url("/internal/articles/" + articleId + "/comments");
    return rows(read("GET " + url, () -> rest.getForObject(url, CommentsDto.class)));
  }

  /** {@code GET /internal/articles/{articleId}/comments/cursor?limit=&direction=[&cursor=]}. */
  public List<CommentRowDto> findByArticleIdWithCursor(
      String articleId, CursorPageParameter<DateTime> page) {
    StringBuilder url =
        new StringBuilder(url("/internal/articles/" + articleId + "/comments/cursor"))
            .append("?limit=")
            .append(page.getLimit())
            .append("&direction=")
            .append(page.isNext() ? "next" : "prev");
    if (page.getCursor() != null) {
      url.append("&cursor=").append(page.getCursor().getMillis());
    }
    String call = url.toString();
    return rows(read("GET " + call, () -> rest.getForObject(call, CommentsDto.class)));
  }

  /** {@code GET /internal/comments/{id}}; a 404 is an empty result. */
  public Optional<CommentRowDto> findById(String id) {
    String url = url("/internal/comments/" + id);
    try {
      CommentDto body = read("GET " + url, () -> rest.getForObject(url, CommentDto.class));
      return body == null ? Optional.empty() : Optional.ofNullable(body.getComment());
    } catch (CommentServiceException e) {
      if (e.getCause() instanceof HttpClientErrorException.NotFound) {
        return Optional.empty();
      }
      throw e;
    }
  }

  /** {@code POST /internal/articles/{articleId}/comments} (idempotent on {@code id}). */
  public CommentRowDto create(Comment comment) {
    String url = url("/internal/articles/" + comment.getArticleId() + "/comments");
    NewCommentRequest request =
        new NewCommentRequest(
            comment.getId(), comment.getBody(), comment.getUserId(), iso(comment.getCreatedAt()));
    CommentDto body =
        write(
            "POST " + url,
            () ->
                rest.postForObject(
                    url, new HttpEntity<>(request, jsonHeaders(true)), CommentDto.class));
    return body == null ? null : body.getComment();
  }

  /** {@code DELETE /internal/articles/{articleId}/comments/{id}} (idempotent, 204). */
  public void delete(String articleId, String commentId) {
    String url = url("/internal/articles/" + articleId + "/comments/" + commentId);
    write(
        "DELETE " + url,
        () ->
            rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(true)), Void.class));
  }

  static String iso(DateTime value) {
    return ISODateTimeFormat.dateTime().withZoneUTC().print(value);
  }

  private static List<CommentRowDto> rows(CommentsDto body) {
    return body == null || body.getComments() == null
        ? Collections.emptyList()
        : body.getComments();
  }

  private String url(String path) {
    String base = properties.getComment().getBaseUrl().toString();
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
      log.debug("comment-service {} failed to connect, retrying once", call);
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

  private static CommentServiceException wrap(String call, RestClientException e) {
    String detail =
        e instanceof HttpStatusCodeException
            ? "status " + ((HttpStatusCodeException) e).getRawStatusCode()
            : e.getClass().getSimpleName();
    return new CommentServiceException(
        "comment-service call failed: " + call + " (" + detail + ")", e);
  }
}
