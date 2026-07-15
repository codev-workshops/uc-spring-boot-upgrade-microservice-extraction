package io.spring.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.spring.core.service.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.mock.mockito.MockBean;

abstract class TestWithCurrentUser {
  @MockBean protected JwtService jwtService;

  protected String userId;
  protected String token;

  @BeforeEach
  public void setUp() {
    userId = "user-1";
    token = "token";
    when(jwtService.getSubFromToken(eq(token))).thenReturn(Optional.of(userId));
  }
}
