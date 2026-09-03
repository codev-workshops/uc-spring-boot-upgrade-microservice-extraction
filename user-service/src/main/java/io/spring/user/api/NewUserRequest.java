package io.spring.user.api;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** id and passwordHash are supplied by the monolith so dual-write produces identical rows. */
@Getter
@Setter
@NoArgsConstructor
public class NewUserRequest {
  @NotBlank(message = "can't be empty")
  private String id;

  @NotBlank(message = "can't be empty")
  private String username;

  @NotBlank(message = "can't be empty")
  private String email;

  @NotBlank(message = "can't be empty")
  private String passwordHash;

  private String bio;
  private String image;
}
