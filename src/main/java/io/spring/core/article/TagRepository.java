package io.spring.core.article;

import java.util.List;
import java.util.Optional;

public interface TagRepository {
  void save(Tag tag);

  Optional<Tag> findById(String id);

  Optional<Tag> findByName(String name);

  List<Tag> findAll();

  void remove(Tag tag);
}
