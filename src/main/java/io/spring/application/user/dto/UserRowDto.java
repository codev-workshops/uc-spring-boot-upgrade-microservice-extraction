package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** User row of the user-service internal API; never carries a password or hash. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRowDto {
  private String id;
  private String username;
  private String email;
  private String bio;
  private String image;
}
