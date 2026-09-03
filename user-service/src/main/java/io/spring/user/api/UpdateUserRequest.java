package io.spring.user.api;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Every field optional; blank/null fields are not written. */
@Getter
@Setter
@NoArgsConstructor
public class UpdateUserRequest {
  private String username;
  private String email;
  private String passwordHash;
  private String bio;
  private String image;
}
