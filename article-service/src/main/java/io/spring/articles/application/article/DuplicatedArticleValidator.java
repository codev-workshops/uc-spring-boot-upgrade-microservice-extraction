package io.spring.articles.application.article;

import io.spring.articles.core.article.Article;
import io.spring.articles.infrastructure.mybatis.readservice.ArticleReadService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class DuplicatedArticleValidator
    implements ConstraintValidator<DuplicatedArticleConstraint, String> {

  private final ArticleReadService articleReadService;

  DuplicatedArticleValidator(ArticleReadService articleReadService) {
    this.articleReadService = articleReadService;
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return articleReadService.findBySlug(Article.toSlug(value)) == null;
  }
}
