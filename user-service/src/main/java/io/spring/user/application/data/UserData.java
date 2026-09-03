package io.spring.user.application.data;

import io.spring.user.core.user.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The user row as exposed to the monolith. Never carries the password hash. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserData {
  private String id;
  private String username;
  private String email;
  private String bio;
  private String image;

  public static UserData of(User user) {
    return new UserData(
        user.getId(), user.getUsername(), user.getEmail(), user.getBio(), user.getImage());
  }
}
