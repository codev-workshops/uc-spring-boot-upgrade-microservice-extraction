package io.spring.infrastructure.extraction.tag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.tag.dto.ArticleIdsDto;
import io.spring.application.tag.dto.ArticleTagsDto;
import io.spring.application.tag.dto.ArticleTagsRowDto;
import io.spring.application.tag.dto.SetTagsRequest;
import io.spring.application.tag.dto.TagDto;
import io.spring.application.tag.dto.TagsDto;
import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the Tag part of article-service's internal API (see
 * docs/microservice-extraction/phases/phase-3-tag.md, section 2.1). Reads are sent without
 * credentials and retried once on connect failure / 503; writes forward the caller's {@code
 * Authorization} header and are never retried. Every failure surfaces as {@link
 * ArticleServiceException}.
 */
@Component
public class ArticleServiceClient {
  private static final Logger log = LoggerFactory.getLogger(ArticleServiceClient.class);

  private final RestTemplate rest;
  private final ExtractionProperties properties;
  private final AuthTokenPropagator auth;

  public ArticleServiceClient(
      RestTemplateBuilder builder, ExtractionProperties properties, AuthTokenPropagator auth) {
    DomainRoute route = properties.getTag();
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

  /** {@code GET /internal/tags}; row order of the service's {@code tags} table. */
  public List<String> allTags() {
    String url = url("/internal/tags");
    TagsDto body = read("GET " + url, () -> rest.getForObject(url, TagsDto.class));
    return body == null || body.getTags() == null ? Collections.emptyList() : body.getTags();
  }

  /** {@code GET /internal/articles/tags?articleIds=a,b,c}; an empty batch is not sent. */
  public List<ArticleTagsRowDto> tagsByArticleIds(List<String> articleIds) {
    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    String url = url("/internal/articles/tags?articleIds=" + String.join(",", articleIds));
    ArticleTagsDto body = read("GET " + url, () -> rest.getForObject(url, ArticleTagsDto.class));
    return body == null || body.getArticleTags() == null
        ? Collections.emptyList()
        : body.getArticleTags();
  }

  /** {@code GET /internal/tags/{name}/article-ids}; unknown tag is an empty list. */
  public List<String> articleIdsByTag(String tagName) {
    String url = url("/internal/tags/{name}/article-ids");
    ArticleIdsDto body =
        read("GET " + url, () -> rest.getForObject(url, ArticleIdsDto.class, tagName));
    return body == null || body.getArticleIds() == null
        ? Collections.emptyList()
        : body.getArticleIds();
  }

  /** {@code PUT /internal/articles/{articleId}/tags} (idempotent set, 204). */
  public void setTags(String articleId, Collection<Tag> tags) {
    String url = url("/internal/articles/" + articleId + "/tags");
    SetTagsRequest request =
        new SetTagsRequest(
            tags.stream()
                .map(tag -> new TagDto(tag.getId(), tag.getName()))
                .collect(Collectors.toList()));
    write(
        "PUT " + url,
        () ->
            rest.exchange(
                url, HttpMethod.PUT, new HttpEntity<>(request, jsonHeaders(true)), Void.class));
  }

  private String url(String path) {
    String base = properties.getTag().getBaseUrl().toString();
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
      log.debug("article-service {} failed to connect, retrying once", call);
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

  private static ArticleServiceException wrap(String call, RestClientException e) {
    String detail =
        e instanceof HttpStatusCodeException
            ? "status " + ((HttpStatusCodeException) e).getRawStatusCode()
            : e.getClass().getSimpleName();
    return new ArticleServiceException(
        "article-service call failed: " + call + " (" + detail + ")", e);
  }
}
