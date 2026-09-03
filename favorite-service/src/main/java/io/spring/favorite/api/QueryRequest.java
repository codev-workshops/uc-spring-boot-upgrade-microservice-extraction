package io.spring.favorite.api;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class QueryRequest {
  @NotBlank private String userId;
  @NotNull private List<String> articleIds;
}
