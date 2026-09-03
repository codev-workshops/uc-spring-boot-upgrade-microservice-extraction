package io.spring.article.api;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

/**
 * POST /internal/articles body. The monolith (dual-write) supplies id, slug, createdAt, updatedAt
 * and tag ids so both stores hold identical rows; absent values fall back to Article's rules.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NewArticleRequest {
  private String id;
  private String slug;

  @NotBlank(message = "can't be empty")
  private String title;

  @NotBlank(message = "can't be empty")
  private String description;

  @NotBlank(message = "can't be empty")
  private String body;

  @NotBlank(message = "can't be empty")
  private String userId;

  private DateTime createdAt;
  private DateTime updatedAt;

  @Valid private List<TagRequest> tags;
}
