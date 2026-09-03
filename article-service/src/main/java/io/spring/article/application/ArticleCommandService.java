package io.spring.article.application;

import io.spring.article.api.exception.DuplicatedArticleException;
import io.spring.article.api.exception.ResourceNotFoundException;
import io.spring.article.application.data.ArticleData;
import io.spring.article.core.article.Article;
import io.spring.article.core.article.ArticleRepository;
import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import java.util.Optional;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleCommandService {
  private final ArticleRepository articleRepository;
  private final TagRepository tagRepository;
  private final ArticleQueryService articleQueryService;

  public ArticleCommandService(
      ArticleRepository articleRepository,
      TagRepository tagRepository,
      ArticleQueryService articleQueryService) {
    this.articleRepository = articleRepository;
    this.tagRepository = tagRepository;
    this.articleQueryService = articleQueryService;
  }

  @Getter
  public static class CreateResult {
    private final ArticleData article;
    private final boolean created;

    public CreateResult(ArticleData article, boolean created) {
      this.article = article;
      this.created = created;
    }
  }

  /**
   * Mirror of MyBatisArticleRepository.save (article + tags in one transaction, tags reused by name
   * / inserted with the supplied id / paired only if absent). Idempotent by id: an existing row
   * with the same id is returned unchanged; another row owning the slug is a 422 title clash.
   */
  @Transactional
  public CreateResult create(Article article) {
    Optional<ArticleData> byId = articleQueryService.findById(article.getId());
    if (byId.isPresent()) {
      return new CreateResult(byId.get(), false);
    }
    if (articleRepository.findBySlug(article.getSlug()).isPresent()) {
      throw new DuplicatedArticleException();
    }
    articleRepository.insert(article);
    for (Tag tag : article.getTags()) {
      Optional<Tag> existing = tagRepository.findByName(tag.getName());
      Tag stored;
      if (existing.isPresent()) {
        stored = existing.get();
      } else {
        tagRepository.insert(tag);
        stored = tag;
      }
      if (!tagRepository.relationExists(article.getId(), stored.getId())) {
        tagRepository.insertRelation(article.getId(), stored.getId());
      }
    }
    return new CreateResult(
        articleQueryService.findById(article.getId()).orElseThrow(IllegalStateException::new),
        true);
  }

  /**
   * Mirror of ArticleMapper.xml#update: blank fields are skipped, the slug is regenerated from a
   * non-blank title, updated_at is NOT written. A slug owned by another article is a 422.
   */
  @Transactional
  public ArticleData update(String id, String title, String description, String body) {
    Article current = articleRepository.findById(id).orElseThrow(ResourceNotFoundException::new);
    String slug = null;
    if (title != null && !title.isEmpty()) {
      slug = Article.toSlug(title);
      Optional<Article> owner = articleRepository.findBySlug(slug);
      if (owner.isPresent() && !owner.get().getId().equals(current.getId())) {
        throw new DuplicatedArticleException();
      }
    }
    articleRepository.update(id, title, slug, description, body);
    return articleQueryService.findById(id).orElseThrow(IllegalStateException::new);
  }

  /** Deletes only the articles row; idempotent. */
  @Transactional
  public void delete(String id) {
    articleRepository.remove(id);
  }
}
