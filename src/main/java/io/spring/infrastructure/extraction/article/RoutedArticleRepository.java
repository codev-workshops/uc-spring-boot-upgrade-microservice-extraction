package io.spring.infrastructure.extraction.article;

import io.spring.application.article.ArticleCommandPort;
import io.spring.application.article.ArticleLookupPort;
import io.spring.application.article.dto.ArticleRowDto;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link ArticleRepository} {@code ArticleCommandService}, {@code ArticleApi}, {@code
 * ArticleFavoriteApi}, {@code CommentsApi} and the GraphQL mutations see. Writes go through the
 * routing {@link ArticleCommandPort}; {@link #findBySlug}/{@link #findById} (the 404 and {@code
 * AuthorizationService.canWriteArticle} checks) read the monolith table while it is authoritative
 * and article-service once {@code extraction.article.write=extracted}.
 */
@Primary
@Repository
public class RoutedArticleRepository implements ArticleRepository, ArticleLookupPort {
  private final MyBatisArticleRepository monolith;
  private final ArticleCommandPort commands;
  private final ArticleDomainServiceClient client;
  private final ExtractionProperties properties;

  public RoutedArticleRepository(
      MyBatisArticleRepository monolith,
      ArticleCommandPort commands,
      ArticleDomainServiceClient client,
      ExtractionProperties properties) {
    this.monolith = monolith;
    this.commands = commands;
    this.client = client;
    this.properties = properties;
  }

  @Override
  public void save(Article article) {
    if (findById(article.getId()).isPresent()) {
      commands.update(article);
    } else {
      commands.create(article);
    }
  }

  @Override
  public Optional<Article> findById(String id) {
    if (properties.getArticle().monolithAuthoritative()) {
      return monolith.findById(id);
    }
    return client.findById(id).map(RoutedArticleRepository::toArticle);
  }

  @Override
  public Optional<Article> findBySlug(String slug) {
    if (properties.getArticle().monolithAuthoritative()) {
      return monolith.findBySlug(slug);
    }
    return client.findBySlug(slug).map(RoutedArticleRepository::toArticle);
  }

  @Override
  public void remove(Article article) {
    commands.delete(article);
  }

  static Article toArticle(ArticleRowDto row) {
    List<Tag> tags =
        row.getTagList() == null
            ? new ArrayList<>()
            : row.getTagList().stream().map(Tag::new).collect(Collectors.toList());
    return new Article(
        row.getId(),
        row.getUserId(),
        row.getSlug(),
        row.getTitle(),
        row.getDescription(),
        row.getBody(),
        tags,
        RemoteArticleQueryAdapter.parse(row.getCreatedAt()),
        RemoteArticleQueryAdapter.parse(row.getUpdatedAt()));
  }
}
