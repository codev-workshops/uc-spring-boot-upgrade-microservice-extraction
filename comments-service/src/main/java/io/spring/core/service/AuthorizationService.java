package io.spring.core.service;

import io.spring.core.comment.Comment;

public class AuthorizationService {
  public static boolean canWriteComment(String userId, String articleAuthorId, Comment comment) {
    return userId.equals(articleAuthorId) || userId.equals(comment.getUserId());
  }
}
