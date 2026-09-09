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
  public static final String MDC_KEY = "correlationId";
  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = resolveCorrelationId(request);
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String resolveCorrelationId(HttpServletRequest request) {
    String id = request.getHeader(CORRELATION_ID_HEADER);
    if (isBlank(id)) {
      id = request.getHeader(REQUEST_ID_HEADER);
    }
    if (isBlank(id)) {
      id = UUID.randomUUID().toString();
    }
    return id.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
