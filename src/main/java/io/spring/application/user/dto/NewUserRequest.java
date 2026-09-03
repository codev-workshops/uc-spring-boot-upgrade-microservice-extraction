package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /internal/users}; the monolith hashes the password and ships only the hash.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewUserRequest {
  private String id;
  private String username;
  private String email;
  private String passwordHash;
  private String bio;
  private String image;
}
