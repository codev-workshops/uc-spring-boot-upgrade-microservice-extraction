package io.spring.infrastructure.extraction.tag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingTagCommandPortTest {
  private final LocalTagCommand local = mock(LocalTagCommand.class);
  private final DualWriteTagCommand dualWrite = mock(DualWriteTagCommand.class);
  private final RemoteTagCommand remote = mock(RemoteTagCommand.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingTagCommandPort port =
      new RoutingTagCommandPort(local, dualWrite, remote, properties, marker);
  private final List<Tag> tags = Collections.singletonList(new Tag("java"));

  @BeforeEach
  public void setUp() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_write_to_the_monolith_and_mark_the_request() {
    port.setTags("a", tags);
    verify(local).setTags("a", tags);
    verifyNoInteractions(dualWrite, remote);
    Assertions.assertTrue(marker.writtenInThisRequest(RoutingTagQueryPort.DOMAIN));
  }

  @Test
  public void write_mode_is_ignored_while_the_flag_is_off() {
    properties.getTag().setWrite(WriteMode.EXTRACTED);
    port.setTags("a", tags);
    verify(local).setTags("a", tags);
    verifyNoInteractions(dualWrite, remote);
  }

  @Test
  public void dual_write_mode_uses_the_dual_writer() {
    properties.getTag().setEnabled(true);
    properties.getTag().setWrite(WriteMode.DUAL_WRITE);
    port.setTags("a", tags);
    verify(dualWrite).setTags("a", tags);
    verifyNoInteractions(local, remote);
  }

  @Test
  public void extracted_mode_writes_only_to_the_service() {
    properties.getTag().setEnabled(true);
    properties.getTag().setWrite(WriteMode.EXTRACTED);
    port.setTags("a", tags);
    verify(remote).setTags("a", tags);
    verifyNoInteractions(local, dualWrite);
  }
}
