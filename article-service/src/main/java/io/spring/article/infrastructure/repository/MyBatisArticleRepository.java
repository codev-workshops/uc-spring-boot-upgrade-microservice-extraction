package io.spring.article.infrastructure.repository;

import io.spring.article.core.article.Article;
import io.spring.article.core.article.ArticleRepository;
import io.spring.article.infrastructure.mybatis.mapper.ArticleMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisArticleRepository implements ArticleRepository {
  private final ArticleMapper mapper;

  public MyBatisArticleRepository(ArticleMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(Article article) {
    mapper.insert(article);
  }

  @Override
  public Optional<Article> findById(String id) {
    return Optional.ofNullable(mapper.findById(id));
  }

  @Override
  public Optional<Article> findBySlug(String slug) {
    return Optional.ofNullable(mapper.findBySlug(slug));
  }

  @Override
  public void update(String id, String title, String slug, String description, String body) {
    if (isBlank(title) && isBlank(description) && isBlank(body)) {
      return;
    }
    mapper.update(id, title, slug, description, body);
  }

  @Override
  public void remove(String id) {
    mapper.delete(id);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isEmpty();
  }
}
