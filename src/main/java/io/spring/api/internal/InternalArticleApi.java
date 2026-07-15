package io.spring.api.internal;

import io.spring.api.exception.ResourceNotFoundException;
import io.spring.core.article.ArticleRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/articles")
@AllArgsConstructor
public class InternalArticleApi {
  private ArticleRepository articleRepository;

  @GetMapping("/{slug}")
  public ArticleResponse findArticle(@PathVariable String slug) {
    return articleRepository
        .findBySlug(slug)
        .map(article -> new ArticleResponse(article.getId(), article.getUserId()))
        .orElseThrow(ResourceNotFoundException::new);
  }
}

@Data
@AllArgsConstructor
class ArticleResponse {
  private String id;
  private String authorId;
}
