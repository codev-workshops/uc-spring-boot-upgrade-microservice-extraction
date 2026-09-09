package io.spring.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  public void should_reuse_incoming_correlation_id_and_clear_mdc_afterwards() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc-123");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

    filter.doFilter(request, response, chain);

    assertEquals("abc-123", seenInChain.get());
    assertEquals("abc-123", response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
  }

  @Test
  public void should_fall_back_to_request_id_header() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "req-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertEquals("req-1", response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
  }

  @Test
  public void should_generate_id_when_header_missing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

    filter.doFilter(request, response, chain);

    String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
    assertNotNull(generated);
    assertFalse(generated.isEmpty());
    assertEquals(generated, seenInChain.get());
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
  }

  @Test
  public void should_clear_mdc_even_when_chain_throws() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      filter.doFilter(
          request,
          response,
          (req, res) -> {
            throw new IllegalStateException("boom");
          });
    } catch (Exception ignored) {
    }
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
  }
}
