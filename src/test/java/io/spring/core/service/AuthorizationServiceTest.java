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
  private User articleAuthor;
  private User commentAuthor;
  private User stranger;
  private Article article;
  private Comment comment;

  @BeforeEach
  public void setUp() {
    articleAuthor = new User("author@test.com", "author", "123", "", "");
    commentAuthor = new User("commenter@test.com", "commenter", "123", "", "");
    stranger = new User("stranger@test.com", "stranger", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), articleAuthor.getId());
    comment = new Comment("comment", commentAuthor.getId(), article.getId());
  }

  @Test
  public void should_allow_owner_to_write_article() {
    assertTrue(AuthorizationService.canWriteArticle(articleAuthor, article));
  }

  @Test
  public void should_not_allow_non_owner_to_write_article() {
    assertFalse(AuthorizationService.canWriteArticle(stranger, article));
    assertFalse(AuthorizationService.canWriteArticle(commentAuthor, article));
  }

  @Test
  public void should_allow_article_author_to_write_comment() {
    assertTrue(AuthorizationService.canWriteComment(articleAuthor, article, comment));
  }

  @Test
  public void should_allow_comment_author_to_write_comment() {
    assertTrue(AuthorizationService.canWriteComment(commentAuthor, article, comment));
  }

  @Test
  public void should_not_allow_other_user_to_write_comment() {
    assertFalse(AuthorizationService.canWriteComment(stranger, article, comment));
  }
}
