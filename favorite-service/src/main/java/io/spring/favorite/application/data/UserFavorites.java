package io.spring.favorite.application.data;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonPropertyOrder({"userId", "articleIds"})
public class UserFavorites {
  private final String userId;
  private final List<String> articleIds;
}
