package io.spring.core.service;

import io.spring.core.comment.Comment;
import io.spring.core.user.User;

public class AuthorizationService {
  public static boolean canWriteComment(User user, String articleUserId, Comment comment) {
    return user.getId().equals(articleUserId) || user.getId().equals(comment.getUserId());
  }
}
