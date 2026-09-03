package io.spring.article.api;

import io.spring.article.api.exception.InvalidAuthenticationException;
import io.spring.article.api.exception.NoAuthorizationException;
import io.spring.article.api.exception.ResourceNotFoundException;
import io.spring.article.application.ArticleCommandService;
import io.spring.article.application.ArticleQueryService;
import io.spring.article.application.CursorPageParameter;
import io.spring.article.application.CursorPageParameter.Direction;
import io.spring.article.application.Page;
import io.spring.article.application.data.ArticleData;
import io.spring.article.application.data.ArticleIdsData;
import io.spring.article.application.data.ArticleListData;
import io.spring.article.core.article.Article;
import io.spring.article.core.tag.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.validation.Valid;
import org.joda.time.DateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the monolith's RemoteArticle* adapters (phase-4-article.md §2.1). */
@RestController
@RequestMapping(path = "/internal/articles")
public class ArticleInternalApi {
  private final ArticleQueryService articleQueryService;
  private final ArticleCommandService articleCommandService;

  public ArticleInternalApi(
      ArticleQueryService articleQueryService, ArticleCommandService articleCommandService) {
    this.articleQueryService = articleQueryService;
    this.articleCommandService = articleCommandService;
  }

  @GetMapping(path = "/{id}")
  public ResponseEntity<Map<String, ArticleData>> byId(@PathVariable String id) {
    return articleQueryService
        .findById(id)
        .map(a -> ResponseEntity.ok(Collections.singletonMap("article", a)))
        .orElseThrow(ResourceNotFoundException::new);
  }

  @GetMapping(path = "/by-slug/{slug}")
  public ResponseEntity<Map<String, ArticleData>> bySlug(@PathVariable String slug) {
    return articleQueryService
        .findBySlug(slug)
        .map(a -> ResponseEntity.ok(Collections.singletonMap("article", a)))
        .orElseThrow(ResourceNotFoundException::new);
  }

  @GetMapping
  public ResponseEntity<Map<String, List<ArticleData>>> byIds(
      @RequestParam(value = "ids", required = false, defaultValue = "") String ids) {
    return ResponseEntity.ok(
        Collections.singletonMap("articles", articleQueryService.findArticles(parseIds(ids))));
  }

  @GetMapping(path = "/ids")
  public ResponseEntity<ArticleIdsData> ids(
      @RequestParam(value = "tag", required = false) String tag,
      @RequestParam(value = "authorId", required = false) String authorId,
      @RequestParam(value = "ids", required = false) String ids,
      @RequestParam(value = "offset", defaultValue = "0") int offset,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return ResponseEntity.ok(
        articleQueryService.findArticleIds(
            tag, authorId, parseAllowList(ids), new Page(offset, limit)));
  }

  @GetMapping(path = "/ids/cursor")
  public ResponseEntity<Map<String, List<String>>> idsWithCursor(
      @RequestParam(value = "tag", required = false) String tag,
      @RequestParam(value = "authorId", required = false) String authorId,
      @RequestParam(value = "ids", required = false) String ids,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "direction", defaultValue = "next") String direction,
      @RequestParam(value = "cursor", required = false) Long cursor) {
    return ResponseEntity.ok(
        Collections.singletonMap(
            "articleIds",
            articleQueryService.findArticleIdsWithCursor(
                tag, authorId, parseAllowList(ids), cursorPage(cursor, limit, direction))));
  }

  @GetMapping(path = "/feed")
  public ResponseEntity<ArticleListData> feed(
      @RequestParam(value = "authorIds", required = false, defaultValue = "") String authorIds,
      @RequestParam(value = "offset", defaultValue = "0") int offset,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return ResponseEntity.ok(
        articleQueryService.findUserFeed(parseIds(authorIds), new Page(offset, limit)));
  }

  @GetMapping(path = "/feed/cursor")
  public ResponseEntity<Map<String, List<ArticleData>>> feedWithCursor(
      @RequestParam(value = "authorIds", required = false, defaultValue = "") String authorIds,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "direction", defaultValue = "next") String direction,
      @RequestParam(value = "cursor", required = false) Long cursor) {
    return ResponseEntity.ok(
        Collections.singletonMap(
            "articles",
            articleQueryService.findUserFeedWithCursor(
                parseIds(authorIds), cursorPage(cursor, limit, direction))));
  }

  @PostMapping
  public ResponseEntity<Map<String, ArticleData>> create(
      @Valid @RequestBody NewArticleRequest request,
      @AuthenticationPrincipal String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    if (!currentUserId.equals(request.getUserId())) {
      throw new NoAuthorizationException();
    }
    List<Tag> tags =
        request.getTags() == null
            ? new ArrayList<>()
            : request.getTags().stream()
                .map(t -> new Tag(t.getId(), t.getName()))
                .collect(Collectors.toList());
    Article article =
        new Article(
            request.getId(),
            request.getSlug(),
            request.getTitle(),
            request.getDescription(),
            request.getBody(),
            request.getUserId(),
            request.getCreatedAt(),
            request.getUpdatedAt(),
            tags);
    ArticleCommandService.CreateResult result = articleCommandService.create(article);
    return ResponseEntity.status(result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(Collections.singletonMap("article", result.getArticle()));
  }

  @PutMapping(path = "/{id}")
  public ResponseEntity<Map<String, ArticleData>> update(
      @PathVariable String id,
      @Valid @RequestBody UpdateArticleRequest request,
      @AuthenticationPrincipal String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    return ResponseEntity.ok(
        Collections.singletonMap(
            "article",
            articleCommandService.update(
                id, request.getTitle(), request.getDescription(), request.getBody())));
  }

  @DeleteMapping(path = "/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String id, @AuthenticationPrincipal String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    articleCommandService.delete(id);
    return ResponseEntity.noContent().build();
  }

  private static CursorPageParameter cursorPage(Long cursor, int limit, String direction) {
    Direction dir = "prev".equalsIgnoreCase(direction) ? Direction.PREV : Direction.NEXT;
    return new CursorPageParameter(cursor == null ? null : new DateTime(cursor), limit, dir);
  }

  /** null when the parameter is absent (no filter), possibly-empty list when present. */
  private static List<String> parseAllowList(String ids) {
    return ids == null ? null : parseIds(ids);
  }

  private static List<String> parseIds(String ids) {
    if (ids == null || ids.trim().isEmpty()) {
      return new ArrayList<>();
    }
    return Arrays.stream(ids.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
