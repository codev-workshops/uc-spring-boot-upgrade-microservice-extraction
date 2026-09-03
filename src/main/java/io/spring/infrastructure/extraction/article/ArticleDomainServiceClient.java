package io.spring.infrastructure.extraction.article;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.application.CursorPageParameter;
import io.spring.application.Page;
import io.spring.application.article.dto.ArticleDto;
import io.spring.application.article.dto.ArticleIdsPageDto;
import io.spring.application.article.dto.ArticleRowDto;
import io.spring.application.article.dto.ArticlesDto;
import io.spring.application.article.dto.NewArticleRequest;
import io.spring.application.article.dto.UpdateArticleRequest;
import io.spring.application.tag.dto.TagDto;
import io.spring.core.article.Article;
import io.spring.infrastructure.extraction.AuthTokenPropagator;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.tag.ArticleServiceException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
 * HTTP client for the Article part of article-service's internal API (see
 * docs/microservice-extraction/phases/phase-4-article.md, section 2.1). Reads are sent without
 * credentials and retried once on connect failure / 503; writes forward the caller's {@code
 * Authorization} header and are never retried. Every failure surfaces as {@link
 * ArticleServiceException}. Configured by {@code extraction.article.*} (the Tag seam has its own
 * client on {@code extraction.tag.*}).
 */
@Component
public class ArticleDomainServiceClient {
  private static final Logger log = LoggerFactory.getLogger(ArticleDomainServiceClient.class);

  private final RestTemplate rest;
  private final ExtractionProperties properties;
  private final AuthTokenPropagator auth;

  public ArticleDomainServiceClient(
      RestTemplateBuilder builder, ExtractionProperties properties, AuthTokenPropagator auth) {
    DomainRoute route = properties.getArticle();
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

  /** {@code GET /internal/articles/{id}}; a 404 is an empty result. */
  public Optional<ArticleRowDto> findById(String id) {
    return single(url("/internal/articles/{id}"), id);
  }

  /** {@code GET /internal/articles/by-slug/{slug}}; a 404 is an empty result. */
  public Optional<ArticleRowDto> findBySlug(String slug) {
    return single(url("/internal/articles/by-slug/{slug}"), slug);
  }

  /**
   * {@code GET /internal/articles?ids=a,b,c} ({@code created_at DESC}); an empty batch is not sent.
   */
  public List<ArticleRowDto> findByIds(List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }
    String url = url("/internal/articles?ids=" + String.join(",", ids));
    return rows(read("GET " + url, () -> rest.getForObject(url, ArticlesDto.class)));
  }

  /** {@code GET /internal/articles/ids?tag=&authorId=&ids=&offset=&limit=}. */
  public ArticleIdsPageDto queryIds(String tag, String authorId, List<String> ids, Page page) {
    StringBuilder url = new StringBuilder(url("/internal/articles/ids?"));
    appendFilters(url, tag, authorId, ids);
    url.append("offset=").append(page.getOffset()).append("&limit=").append(page.getLimit());
    String call = url.toString();
    ArticleIdsPageDto body =
        read("GET " + call, () -> rest.getForObject(call, ArticleIdsPageDto.class, tagVar(tag)));
    return body == null ? new ArticleIdsPageDto(Collections.emptyList(), 0) : body;
  }

  /** {@code GET /internal/articles/ids/cursor?tag=&authorId=&ids=&limit=&direction=[&cursor=]}. */
  public List<String> queryIdsWithCursor(
      String tag, String authorId, List<String> ids, CursorPageParameter<DateTime> page) {
    StringBuilder url = new StringBuilder(url("/internal/articles/ids/cursor?"));
    appendFilters(url, tag, authorId, ids);
    appendCursor(url, page);
    String call = url.toString();
    ArticleIdsPageDto body =
        read("GET " + call, () -> rest.getForObject(call, ArticleIdsPageDto.class, tagVar(tag)));
    return body == null || body.getArticleIds() == null
        ? Collections.emptyList()
        : body.getArticleIds();
  }

  /**
   * {@code GET /internal/articles/feed?authorIds=&offset=&limit=}; an empty author list is not
   * sent.
   */
  public ArticlesDto feed(List<String> authorIds, Page page) {
    if (authorIds.isEmpty()) {
      return new ArticlesDto(Collections.emptyList(), 0);
    }
    String url =
        url("/internal/articles/feed?authorIds=")
            + String.join(",", authorIds)
            + "&offset="
            + page.getOffset()
            + "&limit="
            + page.getLimit();
    ArticlesDto body = read("GET " + url, () -> rest.getForObject(url, ArticlesDto.class));
    return body == null ? new ArticlesDto(Collections.emptyList(), 0) : body;
  }

  /** {@code GET /internal/articles/feed/cursor?authorIds=&limit=&direction=[&cursor=]}. */
  public List<ArticleRowDto> feedWithCursor(
      List<String> authorIds, CursorPageParameter<DateTime> page) {
    if (authorIds.isEmpty()) {
      return Collections.emptyList();
    }
    StringBuilder url =
        new StringBuilder(url("/internal/articles/feed/cursor?authorIds="))
            .append(String.join(",", authorIds))
            .append('&');
    appendCursor(url, page);
    String call = url.toString();
    return rows(read("GET " + call, () -> rest.getForObject(call, ArticlesDto.class)));
  }

  /** {@code POST /internal/articles} (idempotent on {@code id}; full row plus tags). */
  public ArticleRowDto create(Article article) {
    String url = url("/internal/articles");
    NewArticleRequest request =
        new NewArticleRequest(
            article.getId(),
            article.getSlug(),
            article.getTitle(),
            article.getDescription(),
            article.getBody(),
            article.getUserId(),
            iso(article.getCreatedAt()),
            article.getTags().stream()
                .map(tag -> new TagDto(tag.getId(), tag.getName()))
                .collect(Collectors.toList()));
    ArticleDto body =
        write(
            "POST " + url,
            () ->
                rest.postForObject(
                    url, new HttpEntity<>(request, jsonHeaders(true)), ArticleDto.class));
    return body == null ? null : body.getArticle();
  }

  /** {@code PUT /internal/articles/{id}}. */
  public ArticleRowDto update(Article article) {
    String url = url("/internal/articles/" + article.getId());
    UpdateArticleRequest request =
        new UpdateArticleRequest(
            article.getTitle(), article.getDescription(), article.getBody(), article.getSlug());
    ArticleDto body =
        write(
            "PUT " + url,
            () ->
                rest.exchange(
                        url,
                        HttpMethod.PUT,
                        new HttpEntity<>(request, jsonHeaders(true)),
                        ArticleDto.class)
                    .getBody());
    return body == null ? null : body.getArticle();
  }

  /** {@code DELETE /internal/articles/{id}} (idempotent, 204). */
  public void delete(String id) {
    String url = url("/internal/articles/" + id);
    write(
        "DELETE " + url,
        () ->
            rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(true)), Void.class));
  }

  static String iso(DateTime value) {
    return ISODateTimeFormat.dateTime().withZoneUTC().print(value);
  }

  private Optional<ArticleRowDto> single(String url, String variable) {
    try {
      ArticleDto body =
          read("GET " + url, () -> rest.getForObject(url, ArticleDto.class, variable));
      return body == null ? Optional.empty() : Optional.ofNullable(body.getArticle());
    } catch (ArticleServiceException e) {
      if (e.getCause() instanceof HttpClientErrorException.NotFound) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private static void appendFilters(
      StringBuilder url, String tag, String authorId, List<String> ids) {
    if (tag != null) {
      url.append("tag={tag}&");
    }
    if (authorId != null) {
      url.append("authorId=").append(authorId).append('&');
    }
    if (ids != null) {
      url.append("ids=").append(String.join(",", ids)).append('&');
    }
  }

  private static Map<String, String> tagVar(String tag) {
    return tag == null ? Collections.emptyMap() : Collections.singletonMap("tag", tag);
  }

  private static void appendCursor(StringBuilder url, CursorPageParameter<DateTime> page) {
    url.append("limit=")
        .append(page.getLimit())
        .append("&direction=")
        .append(page.isNext() ? "next" : "prev");
    if (page.getCursor() != null) {
      url.append("&cursor=").append(page.getCursor().getMillis());
    }
  }

  private static List<ArticleRowDto> rows(ArticlesDto body) {
    return body == null || body.getArticles() == null
        ? Collections.emptyList()
        : body.getArticles();
  }

  private String url(String path) {
    String base = properties.getArticle().getBaseUrl().toString();
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
