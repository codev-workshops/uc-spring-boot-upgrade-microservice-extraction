package io.spring.infrastructure.extraction;

import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forwards the caller's {@code Authorization: Token <jwt>} header, unchanged, to an extracted
 * service. Only works on the servlet request thread (REST and GraphQL both are).
 */
@Component
public class AuthTokenPropagator {
  public Optional<String> currentAuthorization() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes)) {
      return Optional.empty();
    }
    HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
    return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION));
  }
}
