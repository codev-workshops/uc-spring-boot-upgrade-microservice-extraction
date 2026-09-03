package io.spring.infrastructure.extraction.comment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.spring.core.comment.Comment;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingCommentCommandPortTest {
  private final LocalCommentCommand local = mock(LocalCommentCommand.class);
  private final DualWriteCommentCommand dualWrite = mock(DualWriteCommentCommand.class);
  private final RemoteCommentCommand remote = mock(RemoteCommentCommand.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingCommentCommandPort port =
      new RoutingCommentCommandPort(local, dualWrite, remote, properties, marker);
  private final Comment comment = new Comment("body", "u", "a");

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
    port.create(comment);
    verify(local).create(comment);
    verifyNoInteractions(dualWrite, remote);
    Assertions.assertTrue(marker.writtenInThisRequest(RoutingCommentQueryPort.DOMAIN));
  }

  @Test
  public void write_mode_is_ignored_while_the_flag_is_off() {
    properties.getComment().setWrite(WriteMode.EXTRACTED);
    port.delete("a", "c");
    verify(local).delete("a", "c");
    verifyNoInteractions(dualWrite, remote);
  }

  @Test
  public void dual_write_mode_uses_the_dual_writer() {
    properties.getComment().setEnabled(true);
    properties.getComment().setWrite(WriteMode.DUAL_WRITE);
    port.create(comment);
    port.delete("a", "c");
    verify(dualWrite).create(comment);
    verify(dualWrite).delete("a", "c");
    verifyNoInteractions(local, remote);
  }

  @Test
  public void extracted_mode_writes_only_to_the_service() {
    properties.getComment().setEnabled(true);
    properties.getComment().setWrite(WriteMode.EXTRACTED);
    port.create(comment);
    verify(remote).create(comment);
    verifyNoInteractions(local, dualWrite);
  }
}
