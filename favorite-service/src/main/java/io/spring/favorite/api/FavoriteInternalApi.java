package io.spring.favorite.api;

import io.spring.favorite.api.exception.InvalidAuthenticationException;
import io.spring.favorite.api.exception.InvalidRequestException;
import io.spring.favorite.api.exception.NoAuthorizationException;
import io.spring.favorite.application.FavoriteCommandService;
import io.spring.favorite.application.FavoriteQueryService;
import io.spring.favorite.application.data.ArticleFavoriteCount;
import io.spring.favorite.application.data.FavoriteData;
import io.spring.favorite.application.data.UserFavorites;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the monolith's RemoteFavorite* adapters (phase-1-favorite.md §2.1). */
@RestController
@RequestMapping(path = "/internal/favorites")
public class FavoriteInternalApi {
  private final FavoriteQueryService favoriteQueryService;
  private final FavoriteCommandService favoriteCommandService;
  private final int maxBatchSize;

  public FavoriteInternalApi(
      FavoriteQueryService favoriteQueryService,
      FavoriteCommandService favoriteCommandService,
      @Value("${favorite.max-batch-size:500}") int maxBatchSize) {
    this.favoriteQueryService = favoriteQueryService;
    this.favoriteCommandService = favoriteCommandService;
    this.maxBatchSize = maxBatchSize;
  }

  @PostMapping(path = "/counts")
  public ResponseEntity<Map<String, List<ArticleFavoriteCount>>> counts(
      @Valid @RequestBody CountsRequest request) {
    checkBatch(request.getArticleIds());
    return ResponseEntity.ok(
        Collections.singletonMap(
            "counts", favoriteQueryService.articlesFavoriteCount(request.getArticleIds())));
  }

  @PostMapping(path = "/query")
  public ResponseEntity<UserFavorites> query(@Valid @RequestBody QueryRequest request) {
    checkBatch(request.getArticleIds());
    return ResponseEntity.ok(
        favoriteQueryService.userFavorites(request.getUserId(), request.getArticleIds()));
  }

  @GetMapping(path = "/by-user/{userId}/article-ids")
  public ResponseEntity<UserFavorites> articleIdsFavoritedBy(@PathVariable String userId) {
    return ResponseEntity.ok(favoriteQueryService.articleIdsFavoritedBy(userId));
  }

  @PutMapping(path = "/{articleId}/{userId}")
  public ResponseEntity<FavoriteData> favorite(
      @PathVariable String articleId,
      @PathVariable String userId,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(userId, currentUserId);
    return ResponseEntity.ok(favoriteCommandService.favorite(articleId, userId));
  }

  @DeleteMapping(path = "/{articleId}/{userId}")
  public ResponseEntity<Void> unfavorite(
      @PathVariable String articleId,
      @PathVariable String userId,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(userId, currentUserId);
    favoriteCommandService.unfavorite(articleId, userId);
    return ResponseEntity.noContent().build();
  }

  private void checkBatch(List<String> ids) {
    if (ids.size() > maxBatchSize) {
      throw new InvalidRequestException(
          "articleIds batch too large: " + ids.size() + " > " + maxBatchSize);
    }
    if (ids.stream().anyMatch(id -> id == null || id.isEmpty())) {
      throw new InvalidRequestException("articleIds must not contain null or empty ids");
    }
  }

  private void checkOwner(String userId, String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    if (!currentUserId.equals(userId)) {
      throw new NoAuthorizationException();
    }
  }
}
