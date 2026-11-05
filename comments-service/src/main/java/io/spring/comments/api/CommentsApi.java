package io.spring.comments.api;

import com.fasterxml.jackson.annotation.JsonRootName;
import io.spring.comments.api.exception.ResourceNotFoundException;
import io.spring.comments.application.CommentQueryService;
import io.spring.comments.application.CursorPageParameter;
import io.spring.comments.application.Direction;
import io.spring.comments.application.data.CommentData;
import io.spring.comments.core.comment.Comment;
import io.spring.comments.core.comment.CommentRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class CommentsApi {
  private CommentRepository commentRepository;
  private CommentQueryService commentQueryService;

  @PostMapping("/articles/{articleId}/comments")
  public ResponseEntity<?> createComment(
      @PathVariable("articleId") String articleId,
      @Valid @RequestBody NewCommentParam newCommentParam) {
    String id =
        newCommentParam.getId() == null ? UUID.randomUUID().toString() : newCommentParam.getId();
    Comment comment =
        new Comment(id, newCommentParam.getBody(), newCommentParam.getUserId(), articleId);
    commentRepository.save(comment);
    return ResponseEntity.status(201)
        .body(
            commentResponse(
                commentQueryService
                    .findById(comment.getId())
                    .orElseThrow(ResourceNotFoundException::new)));
  }

  @GetMapping("/articles/{articleId}/comments")
  public ResponseEntity<?> getComments(
      @PathVariable("articleId") String articleId,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) Long cursor,
      @RequestParam(value = "direction", required = false) Direction direction) {
    List<CommentData> comments;
    if (limit == null) {
      comments = commentQueryService.findByArticleId(articleId);
    } else {
      DateTime cursorTime = cursor == null ? null : new DateTime(cursor).withZone(DateTimeZone.UTC);
      comments =
          commentQueryService.findByArticleIdWithCursor(
              articleId, new CursorPageParameter(cursorTime, limit, direction));
    }
    Map<String, Object> body = new HashMap<>();
    body.put("comments", comments);
    return ResponseEntity.ok(body);
  }

  @GetMapping("/comments/{id}")
  public ResponseEntity<?> getComment(@PathVariable("id") String id) {
    return commentQueryService
        .findById(id)
        .map(commentData -> ResponseEntity.ok(commentResponse(commentData)))
        .orElseThrow(ResourceNotFoundException::new);
  }

  @GetMapping("/articles/{articleId}/comments/{id}")
  public ResponseEntity<?> getArticleComment(
      @PathVariable("articleId") String articleId, @PathVariable("id") String id) {
    return commentRepository
        .findById(articleId, id)
        .flatMap(comment -> commentQueryService.findById(comment.getId()))
        .map(commentData -> ResponseEntity.ok(commentResponse(commentData)))
        .orElseThrow(ResourceNotFoundException::new);
  }

  @DeleteMapping("/articles/{articleId}/comments/{id}")
  public ResponseEntity<?> deleteComment(
      @PathVariable("articleId") String articleId, @PathVariable("id") String id) {
    Comment comment =
        commentRepository.findById(articleId, id).orElseThrow(ResourceNotFoundException::new);
    commentRepository.remove(comment);
    return ResponseEntity.noContent().build();
  }

  private Map<String, Object> commentResponse(CommentData commentData) {
    Map<String, Object> body = new HashMap<>();
    body.put("comment", commentData);
    return body;
  }
}

@Getter
@NoArgsConstructor
@JsonRootName("comment")
class NewCommentParam {
  private String id;

  @NotBlank(message = "can't be empty")
  private String body;

  @NotBlank(message = "can't be empty")
  private String userId;
}
