package io.spring.articles.application.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.articles.core.article.Article;
import io.spring.articles.core.article.ArticleRepository;
import io.spring.articles.core.user.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArticleCommandServiceTest {
  private ArticleRepository articleRepository;
  private ArticleCommandService service;

  @BeforeEach
  void setUp() {
    articleRepository = mock(ArticleRepository.class);
    service = new ArticleCommandService(articleRepository);
  }

  @Test
  void should_create_article_owned_by_current_user() {
    Article article =
        service.createArticle(
            new NewArticleParam("My Title", "desc", "body", List.of("java")),
            new CurrentUser("user-1", "john", "token"));
    assertEquals("user-1", article.getUserId());
    assertEquals("my-title", article.getSlug());
    verify(articleRepository).save(article);
  }

  @Test
  void should_update_only_provided_fields() {
    Article article = new Article("Old", "desc", "body", List.of(), "user-1");
    service.updateArticle(article, new UpdateArticleParam("New Title", "", ""));
    assertEquals("new-title", article.getSlug());
    assertEquals("body", article.getBody());
    verify(articleRepository).save(article);
  }

  @Test
  void should_find_and_delete_by_slug() {
    Article article = new Article("Old", "desc", "body", List.of(), "user-1");
    when(articleRepository.findBySlug("old")).thenReturn(Optional.of(article));
    assertEquals(Optional.of(article), service.findBySlug("old"));
    assertTrue(service.findBySlug("missing").isEmpty());
    service.deleteArticle(article);
    verify(articleRepository).remove(article);
  }
}
