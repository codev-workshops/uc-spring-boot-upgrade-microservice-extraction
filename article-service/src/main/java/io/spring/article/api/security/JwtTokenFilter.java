package io.spring.article.api.security;

import io.spring.article.core.service.JwtService;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates "Authorization: Token &lt;jwt&gt;" and authenticates a lightweight principal: the user
 * id from the JWT subject. The service has no users table, so no User is loaded.
 */
public class JwtTokenFilter extends OncePerRequestFilter {
  private static final String HEADER = "Authorization";
  private final JwtService jwtService;

  public JwtTokenFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    boolean authenticatedHere = false;
    Optional<String> userId =
        getTokenString(request.getHeader(HEADER)).flatMap(jwtService::getSubFromToken);
    if (userId.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
      UsernamePasswordAuthenticationToken authenticationToken =
          new UsernamePasswordAuthenticationToken(userId.get(), null, Collections.emptyList());
      authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authenticationToken);
      authenticatedHere = true;
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (authenticatedHere) {
        SecurityContextHolder.clearContext();
      }
    }
  }

  private Optional<String> getTokenString(String header) {
    if (header == null) {
      return Optional.empty();
    }
    String[] split = header.split(" ");
    if (split.length < 2 || !"Token".equals(split[0])) {
      return Optional.empty();
    }
    return Optional.ofNullable(split[1]);
  }
}
