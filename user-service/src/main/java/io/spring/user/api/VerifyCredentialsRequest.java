package io.spring.user.api;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class VerifyCredentialsRequest {
  private String password;

  @Override
  public String toString() {
    return "VerifyCredentialsRequest[password=***]";
  }
}
