package io.spring.user.core.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Row of the users table. password holds the BCrypt hash supplied by the monolith. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class User {
  private String id;
  private String username;
  private String email;
  private String password;
  private String bio;
  private String image;
}
