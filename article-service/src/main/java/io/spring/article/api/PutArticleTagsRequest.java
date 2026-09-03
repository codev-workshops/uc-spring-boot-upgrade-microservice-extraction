package io.spring.article.api;

import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Body of PUT /internal/articles/{articleId}/tags: {"tags":[{"id","name"}]}. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PutArticleTagsRequest {
  @NotNull(message = "can't be missing")
  @Valid
  private List<TagRequest> tags;
}
