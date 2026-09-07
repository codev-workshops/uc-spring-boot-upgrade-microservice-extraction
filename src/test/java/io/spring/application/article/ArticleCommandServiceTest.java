package io.spring.application.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.article.Tag;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ArticleCommandServiceTest {
  private ArticleRepository articleRepository;
  private ArticleCommandService articleCommandService;
  private User user;

  @BeforeEach
  public void setUp() {
    articleRepository = mock(ArticleRepository.class);
    articleCommandService = new ArticleCommandService(articleRepository);
    user = new User("john@test.com", "john", "123", "", "");
  }

  @Test
  public void should_create_article_for_user_and_persist() {
    NewArticleParam param =
        NewArticleParam.builder()
            .title("Hello World")
            .description("desc")
            .body("body")
            .tagList(Arrays.asList("java", "spring"))
            .build();

    Article created = articleCommandService.createArticle(param, user);

    assertNotNull(created);
    assertNotNull(created.getId());
    assertEquals("Hello World", created.getTitle());
    assertEquals("hello-world", created.getSlug());
    assertEquals("desc", created.getDescription());
    assertEquals("body", created.getBody());
    assertEquals(user.getId(), created.getUserId());
    assertTrue(
        created.getTags().stream().map(Tag::getName).collect(Collectors.toList()).contains("java"));

    ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
    verify(articleRepository).save(captor.capture());
    assertSame(created, captor.getValue());
  }

  @Test
  public void should_update_article_fields_and_persist() {
    Article article =
        new Article("old title", "old desc", "old body", Arrays.asList(), user.getId());

    Article updated =
        articleCommandService.updateArticle(
            article, new UpdateArticleParam("new title", "new body", "new desc"));

    assertSame(article, updated);
    assertEquals("new title", updated.getTitle());
    assertEquals("new-title", updated.getSlug());
    assertEquals("new desc", updated.getDescription());
    assertEquals("new body", updated.getBody());
    verify(articleRepository).save(article);
  }
}
