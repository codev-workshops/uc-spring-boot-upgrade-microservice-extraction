package io.spring.infrastructure.extraction.article;

import io.spring.application.article.ArticleCommandPort;
import io.spring.core.article.Article;
import org.springframework.stereotype.Component;

/**
 * Writes to article-service; failures propagate as {@link
 * io.spring.infrastructure.extraction.tag.ArticleServiceException}.
 */
@Component
public class RemoteArticleCommand implements ArticleCommandPort {
  private final ArticleDomainServiceClient client;

  public RemoteArticleCommand(ArticleDomainServiceClient client) {
    this.client = client;
  }

  @Override
  public void create(Article article) {
    client.create(article);
  }

  @Override
  public void update(Article article) {
    client.update(article);
  }

  @Override
  public void delete(Article article) {
    client.delete(article.getId());
  }
}
