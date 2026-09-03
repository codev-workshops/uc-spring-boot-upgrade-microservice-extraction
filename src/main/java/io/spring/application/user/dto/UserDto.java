package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code {"user": row}} envelope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
  private UserRowDto user;
}
