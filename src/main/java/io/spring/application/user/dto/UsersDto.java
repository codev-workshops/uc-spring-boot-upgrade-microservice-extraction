package io.spring.application.user.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code {"users": [row]}} envelope of {@code GET /internal/users?ids=}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsersDto {
  private List<UserRowDto> users;
}
