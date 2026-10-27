package io.spring.articles.core.service;

import io.spring.articles.core.article.Article;
import io.spring.articles.core.user.CurrentUser;

public class AuthorizationService {
  public static boolean canWriteArticle(CurrentUser user, Article article) {
    return user.getId().equals(article.getUserId());
  }
}
