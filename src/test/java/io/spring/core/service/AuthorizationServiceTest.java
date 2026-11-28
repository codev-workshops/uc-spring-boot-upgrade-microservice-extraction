package io.spring.core.service;

import io.spring.core.article.Article;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthorizationServiceTest {
  private User articleAuthor;
  private User commentAuthor;
  private User stranger;
  private Article article;
  private Comment comment;

  @BeforeEach
  public void setUp() {
    articleAuthor = new User("a@test.com", "a", "123", "", "");
    commentAuthor = new User("c@test.com", "c", "123", "", "");
    stranger = new User("s@test.com", "s", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), articleAuthor.getId());
    comment = new Comment("body", commentAuthor.getId(), article.getId());
  }

  @Test
  public void should_only_allow_article_author_to_write_article() {
    Assertions.assertTrue(AuthorizationService.canWriteArticle(articleAuthor, article));
    Assertions.assertFalse(AuthorizationService.canWriteArticle(stranger, article));
  }

  @Test
  public void should_allow_article_author_and_comment_author_to_write_comment() {
    Assertions.assertTrue(AuthorizationService.canWriteComment(articleAuthor, article, comment));
    Assertions.assertTrue(AuthorizationService.canWriteComment(commentAuthor, article, comment));
    Assertions.assertFalse(AuthorizationService.canWriteComment(stranger, article, comment));
  }
}
