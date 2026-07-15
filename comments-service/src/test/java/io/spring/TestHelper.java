package io.spring;

import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.comment.Comment;

public class TestHelper {
  public static CommentData commentDataFixture(Comment comment) {
    return new CommentData(
        comment.getId(),
        comment.getBody(),
        comment.getArticleId(),
        comment.getCreatedAt(),
        comment.getCreatedAt(),
        new ProfileData(comment.getUserId(), "author", "bio", "image", false));
  }
}
