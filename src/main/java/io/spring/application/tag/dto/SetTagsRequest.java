package io.spring.application.tag.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body of {@code PUT /internal/articles/{articleId}/tags} (idempotent set). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetTagsRequest {
  private List<TagDto> tags;
}
