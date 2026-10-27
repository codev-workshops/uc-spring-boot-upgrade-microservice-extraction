package io.spring.articles.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.articles.core.user.CurrentUser;
import io.spring.articles.infrastructure.client.UserServiceClient;
import io.spring.articles.infrastructure.client.UserServiceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the caller by forwarding the Authorization token to the user service. */
public class UserServiceTokenFilter extends OncePerRequestFilter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final UserServiceClient userServiceClient;

  public UserServiceTokenFilter(UserServiceClient userServiceClient) {
    this.userServiceClient = userServiceClient;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<String> token = getTokenString(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (token.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
      try {
        userServiceClient
            .findCurrentUser(token.get())
            .ifPresent(
                user -> {
                  UsernamePasswordAuthenticationToken authentication =
                      new UsernamePasswordAuthenticationToken(
                          new CurrentUser(user.id(), user.username(), token.get()),
                          null,
                          Collections.emptyList());
                  authentication.setDetails(
                      new WebAuthenticationDetailsSource().buildDetails(request));
                  SecurityContextHolder.getContext().setAuthentication(authentication);
                });
      } catch (UserServiceException e) {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MAPPER.writeValue(
            response.getWriter(), Map.of("errors", Map.of("userService", List.of(e.getMessage()))));
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  private Optional<String> getTokenString(String header) {
    if (header == null) {
      return Optional.empty();
    }
    String[] split = header.split(" ");
    if (split.length < 2) {
      return Optional.empty();
    }
    return Optional.ofNullable(split[1]);
  }
}
