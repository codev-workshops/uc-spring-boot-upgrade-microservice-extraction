package io.spring.graphql;

import io.spring.core.user.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class GraphQlDeprecationFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(GraphQlDeprecationFilter.class);
  private static final String RETIRED_RESPONSE =
      "{\"errors\":[{\"message\":\"The GraphQL API has been retired. Use the REST API instead.\"}]}";

  private final GraphQlFeatureProperties graphQlFeatureProperties;
  private final RequestMatcher requestMatcher =
      new OrRequestMatcher(
          new AntPathRequestMatcher("/graphql/**"),
          new AntPathRequestMatcher("/graphiql/**"),
          new AntPathRequestMatcher("/schema.json"));

  public GraphQlDeprecationFilter(GraphQlFeatureProperties graphQlFeatureProperties) {
    this.graphQlFeatureProperties = graphQlFeatureProperties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !requestMatcher.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader(
        "Deprecation",
        "@"
            + graphQlFeatureProperties
                .getDeprecationDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond());
    response.setHeader(
        "Sunset",
        graphQlFeatureProperties
            .getSunsetDate()
            .atStartOfDay(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME));

    User user = getAuthenticatedUser();
    logger.info(
        "Deprecated GraphQL endpoint called: method={} path={} userId={} username={} userAgent={} enabled={}",
        request.getMethod(),
        request.getRequestURI(),
        user == null ? "anonymous" : user.getId(),
        user == null ? "-" : user.getUsername(),
        sanitizeUserAgent(request.getHeader("User-Agent")),
        graphQlFeatureProperties.isEnabled());

    if (!graphQlFeatureProperties.isEnabled()) {
      response.setStatus(HttpServletResponse.SC_GONE);
      response.setContentType("application/json");
      response.getOutputStream().write(RETIRED_RESPONSE.getBytes(StandardCharsets.UTF_8));
      return;
    }

    filterChain.doFilter(request, response);
  }

  private User getAuthenticatedUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof User) {
      return (User) authentication.getPrincipal();
    }
    return null;
  }

  private String sanitizeUserAgent(String userAgent) {
    if (userAgent == null || userAgent.isEmpty()) {
      return "-";
    }
    String sanitized = userAgent.replace('\r', '_').replace('\n', '_');
    return sanitized.length() > 200 ? sanitized.substring(0, 200) : sanitized;
  }
}
