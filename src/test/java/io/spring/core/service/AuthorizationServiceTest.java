package io.spring.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.core.article.Article;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class AuthorizationServiceTest {
  private final User author = new User("author@example.com", "author", "password", "", "");
  private final User otherUser = new User("other@example.com", "other", "password", "", "");
  private final Article article =
      new Article("title", "description", "body", Arrays.asList("java"), author.getId());
  private final Comment authorComment = new Comment("comment", author.getId(), article.getId());
  private final Comment otherComment = new Comment("comment", otherUser.getId(), article.getId());

  @Test
  public void canWriteArticleOnlyForTheAuthor() {
    assertTrue(AuthorizationService.canWriteArticle(author, article));
    assertFalse(AuthorizationService.canWriteArticle(otherUser, article));
  }

  @Test
  public void canWriteCommentForArticleAuthor() {
    assertTrue(AuthorizationService.canWriteComment(author, article, otherComment));
  }

  @Test
  public void canWriteCommentForCommentAuthor() {
    assertTrue(AuthorizationService.canWriteComment(otherUser, article, otherComment));
  }

  @Test
  public void canWriteCommentWhenUserIsBothAuthors() {
    assertTrue(AuthorizationService.canWriteComment(author, article, authorComment));
  }

  @Test
  public void cannotWriteCommentWhenUserIsNeitherAuthor() {
    User unrelatedUser = new User("unrelated@example.com", "unrelated", "password", "", "");
    assertFalse(AuthorizationService.canWriteComment(unrelatedUser, article, otherComment));
  }
}
