package io.spring.article.core.service;

import java.util.Optional;

public interface JwtService {
  String toToken(String userId);

  Optional<String> getSubFromToken(String token);
}
