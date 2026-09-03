package io.spring.article.core.tag;

import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Same shape as the monolith's io.spring.core.article.Tag; ids are UUIDs supplied by the caller.
 */
@Getter
@NoArgsConstructor
public class Tag {
  private String id;
  private String name;

  public Tag(String id, String name) {
    this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
    this.name = name;
  }
}
