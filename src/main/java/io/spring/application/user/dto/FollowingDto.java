package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response of {@code GET /internal/users/{id}/follows/{targetId}}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowingDto {
  private boolean following;
}
