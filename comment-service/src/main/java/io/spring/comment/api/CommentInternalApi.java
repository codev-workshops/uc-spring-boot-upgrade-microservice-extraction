package io.spring.comment.api;

import io.spring.comment.api.exception.InvalidAuthenticationException;
import io.spring.comment.api.exception.InvalidRequestException;
import io.spring.comment.api.exception.NoAuthorizationException;
import io.spring.comment.api.exception.ResourceNotFoundException;
import io.spring.comment.application.CommentCommandService;
import io.spring.comment.application.CommentCommandService.CreateResult;
import io.spring.comment.application.CommentQueryService;
import io.spring.comment.application.CursorPageParameter;
import io.spring.comment.application.CursorPageParameter.Direction;
import io.spring.comment.application.data.CommentData;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the monolith's RemoteComment* adapters (phase-2-comment.md §2.1). */
@RestController
@RequestMapping(path = "/internal")
public class CommentInternalApi {
  private final CommentQueryService commentQueryService;
  private final CommentCommandService commentCommandService;

  public CommentInternalApi(
      CommentQueryService commentQueryService, CommentCommandService commentCommandService) {
    this.commentQueryService = commentQueryService;
    this.commentCommandService = commentCommandService;
  }

  @GetMapping(path = "/articles/{articleId}/comments")
  public ResponseEntity<Map<String, List<CommentData>>> list(@PathVariable String articleId) {
    return ResponseEntity.ok(
        Collections.singletonMap("comments", commentQueryService.findByArticleId(articleId)));
  }

  @GetMapping(path = "/articles/{articleId}/comments/cursor")
  public ResponseEntity<Map<String, List<CommentData>>> listWithCursor(
      @PathVariable String articleId,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "direction", defaultValue = "next") String direction,
      @RequestParam(value = "cursor", required = false) String cursor) {
    CursorPageParameter page =
        new CursorPageParameter(parseCursor(cursor), limit, parseDirection(direction));
    return ResponseEntity.ok(
        Collections.singletonMap(
            "comments", commentQueryService.findByArticleIdWithCursor(articleId, page)));
  }

  @GetMapping(path = "/comments/{id}")
  public ResponseEntity<Map<String, CommentData>> get(
      @PathVariable String id,
      @RequestParam(value = "articleId", required = false) String articleId) {
    CommentData comment =
        commentQueryService
            .findById(id)
            .filter(c -> articleId == null || articleId.equals(c.getArticleId()))
            .orElseThrow(ResourceNotFoundException::new);
    return ResponseEntity.ok(Collections.singletonMap("comment", comment));
  }

  @PostMapping(path = "/articles/{articleId}/comments")
  public ResponseEntity<Map<String, CommentData>> create(
      @PathVariable String articleId,
      @Valid @RequestBody NewCommentRequest request,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(request.getUserId(), currentUserId);
    CreateResult result =
        commentCommandService.create(
            articleId,
            request.getId(),
            request.getBody(),
            request.getUserId(),
            request.getCreatedAt());
    return ResponseEntity.status(result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(Collections.singletonMap("comment", result.getComment()));
  }

  @DeleteMapping(path = "/articles/{articleId}/comments/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String articleId,
      @PathVariable String id,
      @AuthenticationPrincipal String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    commentCommandService.delete(articleId, id);
    return ResponseEntity.noContent().build();
  }

  private static Direction parseDirection(String direction) {
    try {
      return Direction.valueOf(direction.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("direction must be next or prev");
    }
  }

  private static DateTime parseCursor(String cursor) {
    if (cursor == null || cursor.trim().isEmpty()) {
      return null;
    }
    try {
      return new DateTime(Long.parseLong(cursor.trim()), DateTimeZone.UTC);
    } catch (NumberFormatException e) {
      throw new InvalidRequestException("cursor must be epoch millis");
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
