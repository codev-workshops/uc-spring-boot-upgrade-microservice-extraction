package io.spring.article.core.tag;

import java.util.List;
import java.util.Optional;

public interface TagRepository {
  Optional<Tag> findByName(String name);

  void insert(Tag tag);

  boolean relationExists(String articleId, String tagId);

  void insertRelation(String articleId, String tagId);

  /** Tag names of the article in article_tags rowid order. */
  List<String> findTagNames(String articleId);
}
