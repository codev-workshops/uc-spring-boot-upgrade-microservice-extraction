package io.spring.article.api;

import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TagRequest {
  private String id;

  @NotBlank(message = "can't be empty")
  private String name;
}
