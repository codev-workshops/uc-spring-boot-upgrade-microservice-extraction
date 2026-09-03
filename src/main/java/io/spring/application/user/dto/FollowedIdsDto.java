package io.spring.application.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response of {@code GET /internal/users/{id}/followed}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowedIdsDto {
  private List<String> followedIds;
}
