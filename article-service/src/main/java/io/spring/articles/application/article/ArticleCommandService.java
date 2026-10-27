package io.spring.articles.application.article;

import io.spring.articles.core.article.Article;
import io.spring.articles.core.article.ArticleRepository;
import io.spring.articles.core.user.CurrentUser;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@AllArgsConstructor
public class ArticleCommandService {

  private ArticleRepository articleRepository;

  public Article createArticle(@Valid NewArticleParam newArticleParam, CurrentUser creator) {
    Article article =
        new Article(
            newArticleParam.getTitle(),
            newArticleParam.getDescription(),
            newArticleParam.getBody(),
            newArticleParam.getTagList(),
            creator.getId());
    articleRepository.save(article);
    return article;
  }

  public Article updateArticle(Article article, @Valid UpdateArticleParam updateArticleParam) {
    article.update(
        updateArticleParam.getTitle(),
        updateArticleParam.getDescription(),
        updateArticleParam.getBody());
    articleRepository.save(article);
    return article;
  }

  public Optional<Article> findBySlug(String slug) {
    return articleRepository.findBySlug(slug);
  }

  public void deleteArticle(Article article) {
    articleRepository.remove(article);
  }
}
