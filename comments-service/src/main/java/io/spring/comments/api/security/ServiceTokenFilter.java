package io.spring.comments.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that do not carry the shared service token. End-user authentication and
 * authorization happen in the monolith, so only the monolith may call this service.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Service-Token";

  private final byte[] expectedToken;

  public ServiceTokenFilter(@Value("${comments.service.token:}") String token) {
    if (token == null || token.trim().isEmpty()) {
      throw new IllegalStateException(
          "comments.service.token (COMMENTS_SERVICE_TOKEN) must be set");
    }
    this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = request.getHeader(HEADER);
    if (token == null
        || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"message\":\"missing or invalid service token\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
