package io.spring.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {
  private final String internalServiceKey;

  public InternalServiceAuthenticationFilter(String internalServiceKey) {
    this.internalServiceKey = internalServiceKey;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String suppliedServiceKey = request.getHeader("X-Internal-Service-Key");
    byte[] suppliedKeyBytes =
        suppliedServiceKey == null
            ? new byte[0]
            : suppliedServiceKey.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(
        internalServiceKey.getBytes(StandardCharsets.UTF_8), suppliedKeyBytes)) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "comments-service", null, Collections.emptyList()));
    filterChain.doFilter(request, response);
  }
}
