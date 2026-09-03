package io.spring.infrastructure.extraction;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Marks the current request as having written to a domain, so that the read-after-write that builds
 * the response is served by the authoritative store (the monolith while {@code write != extracted})
 * rather than by a possibly lagging mirror. Scoped to the servlet request; a no-op outside one.
 */
@Component
public class ReadAfterWriteMarker {
  private static final String KEY = ReadAfterWriteMarker.class.getName() + ".written";

  public void markWritten(String domain) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.setAttribute(KEY + "." + domain, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
    }
  }

  public boolean writtenInThisRequest(String domain) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    return attributes != null
        && attributes.getAttribute(KEY + "." + domain, RequestAttributes.SCOPE_REQUEST) != null;
  }
}
