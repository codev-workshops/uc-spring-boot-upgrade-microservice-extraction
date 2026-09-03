package io.spring.article.application.data;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/**
 * Raw article row plus tagList (article_tags rowid order). No profileData / favorited /
 * favoritesCount: the monolith composes those (phase-4-article.md §2.1).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({
  "id",
  "slug",
  "title",
  "description",
  "body",
  "userId",
  "createdAt",
  "updatedAt",
  "tagList"
})
public class ArticleData {
  private String id;
  private String slug;
  private String title;
  private String description;
  private String body;
  private String userId;
  private DateTime createdAt;
  private DateTime updatedAt;
  private List<String> tagList;
}
