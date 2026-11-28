package io.spring.application.article;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.article.Tag;
import io.spring.core.user.User;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
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
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
  }

  @Test
  public void should_create_and_save_article_for_creator() {
    NewArticleParam param =
        NewArticleParam.builder()
            .title("How to train your dragon")
            .description("desc")
            .body("body")
            .tagList(Arrays.asList("java", "spring"))
            .build();

    Article article = articleCommandService.createArticle(param, user);

    ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
    verify(articleRepository).save(captor.capture());
    Article saved = captor.getValue();
    Assertions.assertEquals(article, saved);
    Assertions.assertEquals("How to train your dragon", saved.getTitle());
    Assertions.assertEquals("how-to-train-your-dragon", saved.getSlug());
    Assertions.assertEquals(user.getId(), saved.getUserId());
    Assertions.assertEquals(
        Arrays.asList("java", "spring"),
        saved.getTags().stream().map(Tag::getName).collect(Collectors.toList()));
  }

  @Test
  public void should_update_and_save_article() {
    Article article =
        new Article(
            "old title",
            "old desc",
            "old body",
            Arrays.asList("java"),
            user.getId(),
            new DateTime(2020, 1, 1, 0, 0));

    articleCommandService.updateArticle(
        article, new UpdateArticleParam("new title", "new body", ""));

    ArgumentCaptor<Article> captor = ArgumentCaptor.forClass(Article.class);
    verify(articleRepository).save(captor.capture());
    Article saved = captor.getValue();
    Assertions.assertEquals("new title", saved.getTitle());
    Assertions.assertEquals("new-title", saved.getSlug());
    Assertions.assertEquals("new body", saved.getBody());
    Assertions.assertEquals("old desc", saved.getDescription());
    Assertions.assertTrue(saved.getUpdatedAt().isAfter(saved.getCreatedAt()));
  }
}
