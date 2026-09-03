package io.spring.infrastructure.extraction.tag;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * The {@link ArticleRepository} {@code ArticleCommandService}, {@code ArticleApi} and the GraphQL
 * mutations see. Articles stay in the monolith in Phase 3, so every call is delegated to {@link
 * MyBatisArticleRepository}; on a create, once its {@code @Transactional save} (article, tags and
 * relations) has committed, the article's tag set is additionally pushed through the routing {@link
 * TagCommandPort} when {@code extraction.tag.write} asks for a remote copy. Update and delete never
 * touch tags, exactly like the monolith.
 */
@Primary
@Repository
public class RoutingArticleRepository implements ArticleRepository {
  private final MyBatisArticleRepository monolith;
  private final TagCommandPort tags;
  private final ExtractionProperties properties;

  public RoutingArticleRepository(
      MyBatisArticleRepository monolith, TagCommandPort tags, ExtractionProperties properties) {
    this.monolith = monolith;
    this.tags = tags;
    this.properties = properties;
  }

  @Override
  public void save(Article article) {
    boolean mirrorTags =
        properties.getTag().writesRemote() && !monolith.findById(article.getId()).isPresent();
    monolith.save(article);
    if (mirrorTags) {
      tags.setTags(article.getId(), article.getTags());
    }
  }

  @Override
  public Optional<Article> findById(String id) {
    return monolith.findById(id);
  }

  @Override
  public Optional<Article> findBySlug(String slug) {
    return monolith.findBySlug(slug);
  }

  @Override
  public void remove(Article article) {
    monolith.remove(article);
  }
}
