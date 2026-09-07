package io.spring.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.core.article.Article;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthorizationServiceTest {
  private User author;
  private User commenter;
  private User stranger;
  private Article article;
  private Comment comment;

  @BeforeEach
  public void setUp() {
    author = new User("author@test.com", "author", "123", "", "");
    commenter = new User("commenter@test.com", "commenter", "123", "", "");
    stranger = new User("stranger@test.com", "stranger", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), author.getId());
    comment = new Comment("content", commenter.getId(), article.getId());
  }

  @Test
  public void article_author_can_write_article() {
    assertTrue(AuthorizationService.canWriteArticle(author, article));
  }

  @Test
  public void other_user_cannot_write_article() {
    assertFalse(AuthorizationService.canWriteArticle(stranger, article));
  }

  @Test
  public void article_author_can_write_comment() {
    assertTrue(AuthorizationService.canWriteComment(author, article, comment));
  }

  @Test
  public void comment_author_can_write_comment() {
    assertTrue(AuthorizationService.canWriteComment(commenter, article, comment));
  }

  @Test
  public void stranger_cannot_write_comment() {
    assertFalse(AuthorizationService.canWriteComment(stranger, article, comment));
  }
}
