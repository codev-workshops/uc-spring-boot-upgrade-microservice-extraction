package io.spring.article.core.article;

import io.spring.article.core.tag.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/**
 * Same shape as the monolith's io.spring.core.article.Article. id, slug, createdAt, updatedAt and
 * tag ids are supplied by the caller (so dual-write produces identical rows); missing values fall
 * back to the monolith's generation rules (UUID id, toSlug(title), now, updatedAt == createdAt).
 */
@Getter
@NoArgsConstructor
@EqualsAndHashCode(of = {"id"})
public class Article {
  private String userId;
  private String id;
  private String slug;
  private String title;
  private String description;
  private String body;
  private List<Tag> tags;
  private DateTime createdAt;
  private DateTime updatedAt;

  public Article(
      String id,
      String slug,
      String title,
      String description,
      String body,
      String userId,
      DateTime createdAt,
      DateTime updatedAt,
      List<Tag> tags) {
    this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
    this.slug = slug == null || slug.isEmpty() ? toSlug(title) : slug;
    this.title = title;
    this.description = description;
    this.body = body;
    this.userId = userId;
    this.createdAt = createdAt == null ? new DateTime() : createdAt;
    this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    this.tags = tags == null ? new ArrayList<>() : tags;
  }

  /** Verbatim copy of the monolith's Article.toSlug. */
  public static String toSlug(String title) {
    return title.toLowerCase().replaceAll("[\\&|[\\uFE30-\\uFFA0]|\\’|\\”|\\s\\?\\,\\.]+", "-");
  }
}
