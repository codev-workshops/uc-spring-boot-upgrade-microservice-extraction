package io.spring.article.infrastructure.repository;

import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import io.spring.article.infrastructure.mybatis.mapper.TagMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTagRepository implements TagRepository {
  private final TagMapper mapper;

  public MyBatisTagRepository(TagMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Tag> findByName(String name) {
    return Optional.ofNullable(mapper.findByName(name));
  }

  @Override
  public void insert(Tag tag) {
    mapper.insert(tag);
  }

  @Override
  public boolean relationExists(String articleId, String tagId) {
    return mapper.countRelation(articleId, tagId) > 0;
  }

  @Override
  public void insertRelation(String articleId, String tagId) {
    mapper.insertRelation(articleId, tagId);
  }

  @Override
  public List<String> findTagNames(String articleId) {
    return mapper.findTagNames(articleId);
  }
}
