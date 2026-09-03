package io.spring.application.favorite.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response body of {@code POST /internal/favorites/counts}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteCountsDto {
  private List<FavoriteCountDto> counts = new ArrayList<>();
}
