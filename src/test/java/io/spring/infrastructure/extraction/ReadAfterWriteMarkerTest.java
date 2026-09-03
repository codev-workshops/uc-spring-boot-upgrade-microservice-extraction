package io.spring.infrastructure.extraction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class ReadAfterWriteMarkerTest {
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void is_a_noop_outside_a_request() {
    marker.markWritten("favorite");
    Assertions.assertFalse(marker.writtenInThisRequest("favorite"));
  }

  @Test
  public void remembers_the_write_for_the_current_request_only() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    Assertions.assertFalse(marker.writtenInThisRequest("favorite"));
    marker.markWritten("favorite");
    Assertions.assertTrue(marker.writtenInThisRequest("favorite"));
    Assertions.assertFalse(marker.writtenInThisRequest("comment"));

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    Assertions.assertFalse(marker.writtenInThisRequest("favorite"));
  }
}
