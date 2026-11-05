package io.spring.infrastructure.comments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request/response envelopes of the comments-service REST API. */
final class CommentEnvelopes {
  private CommentEnvelopes() {}

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  static class NewCommentRequest {
    private NewComment comment;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  static class NewComment {
    private String id;
    private String body;
    private String userId;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class CommentResponse {
    private RemoteComment comment;
  }

  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class CommentsResponse {
    private List<RemoteComment> comments = new ArrayList<>();
  }
}
