package io.spring.user.core.user;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Partial update of a users row; null or blank fields are left untouched. */
@Getter
@AllArgsConstructor
public class UserUpdate {
  private String id;
  private String username;
  private String email;
  private String password;
  private String bio;
  private String image;
}
