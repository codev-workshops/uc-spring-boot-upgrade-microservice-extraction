package io.spring.application.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response of {@code POST /internal/users/{id}/credentials/verify}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialsVerifiedDto {
  private boolean valid;
}
