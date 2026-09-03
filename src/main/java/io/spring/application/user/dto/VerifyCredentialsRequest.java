package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code POST /internal/users/{id}/credentials/verify}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCredentialsRequest {
  private String password;
}
