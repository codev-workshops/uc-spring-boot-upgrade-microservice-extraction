package io.spring.graphql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import graphql.execution.DataFetcherResult;
import io.spring.api.exception.NoAuthorizationException;
import io.spring.api.exception.ResourceNotFoundException;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.article.NewArticleParam;
import io.spring.application.article.UpdateArticleParam;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.favorite.ArticleFavorite;
import io.spring.core.favorite.ArticleFavoriteRepository;
import io.spring.core.user.User;
import io.spring.graphql.exception.AuthenticationException;
import io.spring.graphql.types.ArticlePayload;
import io.spring.graphql.types.CreateArticleInput;
import io.spring.graphql.types.DeletionStatus;
import io.spring.graphql.types.UpdateArticleInput;
import java.util.Arrays;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ArticleMutationTest extends GraphQLTestBase {
  private ArticleCommandService articleCommandService;
  private ArticleFavoriteRepository articleFavoriteRepository;
  private ArticleRepository articleRepository;
  private ArticleMutation articleMutation;
  private User user;
  private User anotherUser;
  private DateTime createdAt;

  @BeforeEach
  public void setUp() {
    articleCommandService = mock(ArticleCommandService.class);
    articleFavoriteRepository = mock(ArticleFavoriteRepository.class);
    articleRepository = mock(ArticleRepository.class);
    articleMutation =
        new ArticleMutation(articleCommandService, articleFavoriteRepository, articleRepository);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
    anotherUser = new User("other@test.com", "other", "123", "", "");
    createdAt = new DateTime(2020, 1, 1, 0, 0);
  }

  private Article buildArticle(User owner) {
    return new Article("title", "desc", "body", Arrays.asList("java"), owner.getId(), createdAt);
  }

  @Test
  public void should_reject_create_article_for_anonymous_user() {
    anonymous();
    CreateArticleInput input =
        CreateArticleInput.newBuilder().title("title").description("desc").body("body").build();

    Assertions.assertThrows(
        AuthenticationException.class, () -> articleMutation.createArticle(input));
  }

  @Test
  public void should_create_article_with_empty_tag_list_when_tags_missing() {
    login(user);
    Article created = buildArticle(user);
    when(articleCommandService.createArticle(any(), eq(user))).thenReturn(created);
    CreateArticleInput input =
        CreateArticleInput.newBuilder().title("title").description("desc").body("body").build();

    DataFetcherResult<ArticlePayload> result = articleMutation.createArticle(input);

    ArgumentCaptor<NewArticleParam> captor = ArgumentCaptor.forClass(NewArticleParam.class);
    verify(articleCommandService).createArticle(captor.capture(), eq(user));
    Assertions.assertEquals("title", captor.getValue().getTitle());
    Assertions.assertEquals("desc", captor.getValue().getDescription());
    Assertions.assertEquals("body", captor.getValue().getBody());
    Assertions.assertTrue(captor.getValue().getTagList().isEmpty());
    Assertions.assertEquals(created, result.getLocalContext());
  }

  @Test
  public void should_create_article_with_given_tags() {
    login(user);
    when(articleCommandService.createArticle(any(), eq(user))).thenReturn(buildArticle(user));
    CreateArticleInput input =
        CreateArticleInput.newBuilder()
            .title("title")
            .description("desc")
            .body("body")
            .tagList(Arrays.asList("java", "spring"))
            .build();

    articleMutation.createArticle(input);

    ArgumentCaptor<NewArticleParam> captor = ArgumentCaptor.forClass(NewArticleParam.class);
    verify(articleCommandService).createArticle(captor.capture(), eq(user));
    Assertions.assertEquals(Arrays.asList("java", "spring"), captor.getValue().getTagList());
  }

  @Test
  public void should_update_article_of_author() {
    login(user);
    Article article = buildArticle(user);
    willAnswer(inv -> Optional.of(buildArticle(user)))
        .given(articleRepository)
        .findBySlug(eq(article.getSlug()));
    Article updated = buildArticle(user);
    when(articleCommandService.updateArticle(any(), any())).thenReturn(updated);
    UpdateArticleInput changes =
        UpdateArticleInput.newBuilder().title("new title").body("new body").build();

    DataFetcherResult<ArticlePayload> result =
        articleMutation.updateArticle(article.getSlug(), changes);

    ArgumentCaptor<UpdateArticleParam> captor = ArgumentCaptor.forClass(UpdateArticleParam.class);
    verify(articleCommandService).updateArticle(any(), captor.capture());
    Assertions.assertEquals("new title", captor.getValue().getTitle());
    Assertions.assertEquals("new body", captor.getValue().getBody());
    Assertions.assertNull(captor.getValue().getDescription());
    Assertions.assertEquals(updated, result.getLocalContext());
  }

  @Test
  public void should_reject_update_article_of_other_author() {
    login(user);
    Article article = buildArticle(anotherUser);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    Assertions.assertThrows(
        NoAuthorizationException.class,
        () ->
            articleMutation.updateArticle(
                article.getSlug(), UpdateArticleInput.newBuilder().title("x").build()));
    verify(articleCommandService, never()).updateArticle(any(), any());
  }

  @Test
  public void should_throw_not_found_when_updating_unknown_article() {
    login(user);
    when(articleRepository.findBySlug(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class,
        () -> articleMutation.updateArticle("missing", UpdateArticleInput.newBuilder().build()));
  }

  @Test
  public void should_reject_update_article_for_anonymous_user() {
    anonymous();
    Article article = buildArticle(user);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    Assertions.assertThrows(
        AuthenticationException.class,
        () ->
            articleMutation.updateArticle(
                article.getSlug(), UpdateArticleInput.newBuilder().build()));
  }

  @Test
  public void should_favorite_article() {
    login(user);
    Article article = buildArticle(anotherUser);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    DataFetcherResult<ArticlePayload> result = articleMutation.favoriteArticle(article.getSlug());

    ArgumentCaptor<ArticleFavorite> captor = ArgumentCaptor.forClass(ArticleFavorite.class);
    verify(articleFavoriteRepository).save(captor.capture());
    Assertions.assertEquals(article.getId(), captor.getValue().getArticleId());
    Assertions.assertEquals(user.getId(), captor.getValue().getUserId());
    Assertions.assertEquals(article, result.getLocalContext());
  }

  @Test
  public void should_throw_not_found_when_favoriting_unknown_article() {
    login(user);
    when(articleRepository.findBySlug(eq("missing"))).thenReturn(Optional.empty());

    Assertions.assertThrows(
        ResourceNotFoundException.class, () -> articleMutation.favoriteArticle("missing"));
    verify(articleFavoriteRepository, never()).save(any());
  }

  @Test
  public void should_unfavorite_article_when_favorite_exists() {
    login(user);
    Article article = buildArticle(anotherUser);
    ArticleFavorite favorite = new ArticleFavorite(article.getId(), user.getId());
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
    when(articleFavoriteRepository.find(eq(article.getId()), eq(user.getId())))
        .thenReturn(Optional.of(favorite));

    DataFetcherResult<ArticlePayload> result = articleMutation.unfavoriteArticle(article.getSlug());

    verify(articleFavoriteRepository).remove(eq(favorite));
    Assertions.assertEquals(article, result.getLocalContext());
  }

  @Test
  public void should_not_remove_anything_when_article_was_not_favorited() {
    login(user);
    Article article = buildArticle(anotherUser);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));
    when(articleFavoriteRepository.find(eq(article.getId()), eq(user.getId())))
        .thenReturn(Optional.empty());

    DataFetcherResult<ArticlePayload> result = articleMutation.unfavoriteArticle(article.getSlug());

    verify(articleFavoriteRepository, never()).remove(any());
    Assertions.assertEquals(article, result.getLocalContext());
  }

  @Test
  public void should_delete_article_of_author() {
    login(user);
    Article article = buildArticle(user);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    DeletionStatus status = articleMutation.deleteArticle(article.getSlug());

    Assertions.assertTrue(status.getSuccess());
    verify(articleRepository).remove(eq(article));
  }

  @Test
  public void should_reject_delete_article_of_other_author() {
    login(user);
    Article article = buildArticle(anotherUser);
    when(articleRepository.findBySlug(eq(article.getSlug()))).thenReturn(Optional.of(article));

    Assertions.assertThrows(
        NoAuthorizationException.class, () -> articleMutation.deleteArticle(article.getSlug()));
    verify(articleRepository, never()).remove(any());
  }

  @Test
  public void should_reject_delete_article_for_anonymous_user() {
    anonymous();

    Assertions.assertThrows(
        AuthenticationException.class, () -> articleMutation.deleteArticle("any"));
  }
}
