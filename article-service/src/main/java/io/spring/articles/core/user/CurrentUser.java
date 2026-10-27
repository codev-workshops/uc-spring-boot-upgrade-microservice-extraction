package io.spring.articles.core.user;

import lombok.Value;

/** Authenticated caller as resolved by the user service, plus the token to forward. */
@Value
public class CurrentUser {
  String id;
  String username;
  String token;
}
