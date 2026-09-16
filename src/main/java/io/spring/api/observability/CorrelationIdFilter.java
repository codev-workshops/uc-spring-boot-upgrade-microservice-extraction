package io.spring.api.observability;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = request.getHeader("X-Correlation-Id");
    if (correlationId == null) {
      correlationId = request.getHeader("X-Request-Id");
    }
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    MDC.put("correlationId", correlationId);
    response.setHeader("X-Correlation-Id", correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
