package io.spring.infrastructure.extraction.article;

import io.spring.application.article.ArticleCommandPort;
import io.spring.core.article.Article;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.tag.RoutingArticleRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import org.springframework.stereotype.Component;

/**
 * Writes to the monolith {@code articles} tables ({@code @Transactional} in the repository). While
 * Article writes stay in the monolith a create goes through the Phase 3 {@link
 * RoutingArticleRepository} so the standalone tag mirror ({@code extraction.tag.write}) keeps
 * running; once Article writes are mirrored or extracted the tags travel inside {@code POST
 * /internal/articles} and that mirror is skipped.
 */
@Component
public class LocalArticleCommand implements ArticleCommandPort {
  private final MyBatisArticleRepository repository;
  private final RoutingArticleRepository phase3;
  private final ExtractionProperties properties;

  public LocalArticleCommand(
      MyBatisArticleRepository repository,
      RoutingArticleRepository phase3,
      ExtractionProperties properties) {
    this.repository = repository;
    this.phase3 = phase3;
    this.properties = properties;
  }

  @Override
  public void create(Article article) {
    if (properties.getArticle().writesRemote()) {
      repository.save(article);
    } else {
      phase3.save(article);
    }
  }

  @Override
  public void update(Article article) {
    repository.save(article);
  }

  @Override
  public void delete(Article article) {
    repository.remove(article);
  }
}
