package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import graphql.execution.DataFetcherResult;
import io.spring.api.exception.NoAuthorizationException;
import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.CommentQueryService;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.comment.Comment;
import io.spring.core.comment.CommentRepository;
import io.spring.core.user.User;
import io.spring.graphql.exception.AuthenticationException;
import io.spring.graphql.types.CommentPayload;
import io.spring.graphql.types.DeletionStatus;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class CommentMutationTest extends GraphQLTestBase {
  private ArticleRepository articleRepository;
  private CommentRepository commentRepository;
  private CommentQueryService commentQueryService;
  private CommentMutation commentMutation;
  private User user;
  private User articleAuthor;
  private Article article;

  @BeforeEach
  public void setUp() {
    articleRepository = mock(ArticleRepository.class);
    commentRepository = mock(CommentRepository.class);
    commentQueryService = mock(CommentQueryService.class);
    commentMutation =
        new CommentMutation(articleRepository, commentRepository, commentQueryService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    articleAuthor = new User("author@test.com", "author", "123", "", "");
    article = new Article("title", "desc", "body", Arrays.asList("java"), articleAuthor.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
  }

  @Test
  public void should_create_comment_for_current_user() {
    login(user);
    DateTime now = new DateTime();
    CommentData commentData =
        new CommentData(
            "id",
            "nice",
            article.getId(),
            now,
            now,
            new ProfileData(user.getId(), user.getUsername(), "", "", false));
    when(commentQueryService.findById(anyString(), eq(user))).thenReturn(Optional.of(commentData));

    DataFetcherResult<CommentPayload> result =
        commentMutation.createComment(article.getSlug(), "nice");

    ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
    verify(commentRepository).save(captor.capture());
    Assertions.assertEquals("nice", captor.getValue().getBody());
    Assertions.assertEquals(user.getId(), captor.getValue().getUserId());
    Assertions.assertEquals(article.getId(), captor.getValue().getArticleId());
    verify(commentQueryService).findById(eq(captor.getValue().getId()), eq(user));
    Assertions.assertEquals(commentData, result.getLocalContext());
  }

  @Test
  public void should_reject_create_comment_for_anonymous_user() {
    anonymous();

    Assertions.assertThrows(
        AuthenticationException.class,
        () -> commentMutation.createComment(article.getSlug(), "nice"));
    verify(commentRepository, never()).save(any());
  }

  @Test
  public void should_throw_not_found_when_commenting_unknown_article() {
    login(user);
    when(articleRepository.findBySlug(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> commentMutation.createComment("missing", "nice"));
  }

  @Test
  public void should_throw_not_found_if_created_comment_cannot_be_read() {
    login(user);
    when(commentQueryService.findById(anyString(), eq(user))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class,
        () -> commentMutation.createComment(article.getSlug(), "nice"));
  }

  @Test
  public void should_allow_comment_author_to_delete_comment() {
    login(user);
    Comment comment = new Comment("body", user.getId(), article.getId());
    when(commentRepository.findById(eq(article.getId()), eq(comment.getId())))
        .thenReturn(Optional.of(comment));

    DeletionStatus status = commentMutation.removeComment(article.getSlug(), comment.getId());

    Assertions.assertTrue(status.getSuccess());
    verify(commentRepository).remove(eq(comment));
  }

  @Test
  public void should_allow_article_author_to_delete_comment() {
    login(articleAuthor);
    Comment comment = new Comment("body", user.getId(), article.getId());
    when(commentRepository.findById(eq(article.getId()), eq(comment.getId())))
        .thenReturn(Optional.of(comment));

    DeletionStatus status = commentMutation.removeComment(article.getSlug(), comment.getId());

    Assertions.assertTrue(status.getSuccess());
    verify(commentRepository).remove(eq(comment));
  }

  @Test
  public void should_reject_delete_comment_by_other_user() {
    User stranger = new User("stranger@test.com", "stranger", "123", "", "");
    login(stranger);
    Comment comment = new Comment("body", user.getId(), article.getId());
    when(commentRepository.findById(eq(article.getId()), eq(comment.getId())))
        .thenReturn(Optional.of(comment));

    Assertions.assertThrows(
        NoAuthorizationException.class,
        () -> commentMutation.removeComment(article.getSlug(), comment.getId()));
    verify(commentRepository, never()).remove(any());
  }

  @Test
  public void should_throw_not_found_when_deleting_unknown_comment() {
    login(user);
    when(commentRepository.findById(eq(article.getId()), eq("missing")))
        .thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class,
        () -> commentMutation.removeComment(article.getSlug(), "missing"));
  }

  @Test
  public void should_reject_delete_comment_for_anonymous_user() {
    anonymous();

    Assertions.assertThrows(
        AuthenticationException.class,
        () -> commentMutation.removeComment(article.getSlug(), "id"));
  }
}
