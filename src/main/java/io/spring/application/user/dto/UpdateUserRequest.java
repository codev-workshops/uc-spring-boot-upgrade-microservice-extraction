package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code PUT /internal/users/{id}}; blank fields are skipped by the service. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {
  private String username;
  private String email;
  private String passwordHash;
  private String bio;
  private String image;
}
