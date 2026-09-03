package io.spring.core.service;

import io.spring.core.article.Article;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AuthorizationServiceTest {
  private User author;
  private User commenter;
  private User stranger;
  private Article article;

  @BeforeEach
  public void setUp() {
    author = new User("author@test.com", "author", "123", "", "");
    commenter = new User("commenter@test.com", "commenter", "123", "", "");
    stranger = new User("stranger@test.com", "stranger", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), author.getId());
  }

  @Test
  public void should_allow_article_author_to_write_article() {
    Assertions.assertTrue(AuthorizationService.canWriteArticle(author, article));
  }

  @Test
  public void should_forbid_other_user_to_write_article() {
    Assertions.assertFalse(AuthorizationService.canWriteArticle(stranger, article));
  }

  @Test
  public void should_allow_comment_author_to_write_comment() {
    Comment comment = new Comment("body", commenter.getId(), article.getId());
    Assertions.assertTrue(AuthorizationService.canWriteComment(commenter, article, comment));
  }

  @Test
  public void should_allow_article_author_to_write_others_comment() {
    Comment comment = new Comment("body", commenter.getId(), article.getId());
    Assertions.assertTrue(AuthorizationService.canWriteComment(author, article, comment));
  }

  @Test
  public void should_forbid_unrelated_user_to_write_comment() {
    Comment comment = new Comment("body", commenter.getId(), article.getId());
    Assertions.assertFalse(AuthorizationService.canWriteComment(stranger, article, comment));
  }
}
