package io.spring.application.article;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spring.TestHelper;
import io.spring.application.ArticleQueryService;
import io.spring.core.user.User;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class DuplicatedArticleValidatorTest {
  private ArticleQueryService articleQueryService;
  private DuplicatedArticleValidator validator;

  @BeforeEach
  public void setUp() {
    articleQueryService = mock(ArticleQueryService.class);
    validator = new DuplicatedArticleValidator();
    ReflectionTestUtils.setField(validator, "articleQueryService", articleQueryService);
  }

  @Test
  public void should_reject_title_whose_slug_already_exists() {
    when(articleQueryService.findBySlug(eq("my-title"), isNull()))
        .thenReturn(
            Optional.of(
                TestHelper.articleDataFixture("1", new User("a@b.com", "a", "123", "", ""))));

    Assertions.assertFalse(validator.isValid("My Title", null));
  }

  @Test
  public void should_accept_title_with_new_slug() {
    when(articleQueryService.findBySlug(eq("fresh-title"), isNull())).thenReturn(Optional.empty());

    Assertions.assertTrue(validator.isValid("Fresh Title", null));
  }
}
