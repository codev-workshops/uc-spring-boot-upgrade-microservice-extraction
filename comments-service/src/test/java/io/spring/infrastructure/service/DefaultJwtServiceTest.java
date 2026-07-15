package io.spring.infrastructure.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Date;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DefaultJwtServiceTest {
  private static final String SECRET =
      "test-secret-test-secret-test-secret-test-secret-test-secret-test-secret-123456789";

  private DefaultJwtService jwtService;

  @BeforeEach
  public void setUp() {
    jwtService = new DefaultJwtService(SECRET);
  }

  @Test
  public void shouldAcceptTokenSignedWithSharedSecret() {
    String token = token("user-1", new Date(System.currentTimeMillis() + 60_000));

    Optional<String> subject = jwtService.getSubFromToken(token);

    assertTrue(subject.isPresent());
    assertEquals("user-1", subject.get());
  }

  @Test
  public void shouldRejectInvalidToken() {
    assertFalse(jwtService.getSubFromToken("invalid").isPresent());
  }

  @Test
  public void shouldRejectExpiredToken() {
    String token = token("user-1", new Date(System.currentTimeMillis() - 60_000));

    assertFalse(jwtService.getSubFromToken(token).isPresent());
  }

  private String token(String subject, Date expiration) {
    SignatureAlgorithm algorithm = SignatureAlgorithm.HS512;
    return Jwts.builder()
        .setSubject(subject)
        .setExpiration(expiration)
        .signWith(new SecretKeySpec(SECRET.getBytes(), algorithm.getJcaName()))
        .compact();
  }
}
