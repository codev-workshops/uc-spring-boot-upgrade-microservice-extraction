package io.spring.infrastructure.extraction.comment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.CommentData;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.LoggingShadowComparator;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.mybatis.readservice.CommentReadService;
import java.util.Collections;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingCommentQueryPortTest {
  private final CommentReadService monolith = mock(CommentReadService.class);
  private final RemoteCommentQueryAdapter remote = mock(RemoteCommentQueryAdapter.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final LoggingShadowComparator shadow = new LoggingShadowComparator(Runnable::run);
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingCommentQueryPort port =
      new RoutingCommentQueryPort(monolith, remote, properties, shadow, marker);
  private final CursorPageParameter<DateTime> page =
      new CursorPageParameter<>(null, 10, Direction.NEXT);

  private final CommentData local = new CommentData("c1", "local", "a", null, null, null);
  private final CommentData extracted = new CommentData("c1", "remote", "a", null, null, null);

  @BeforeEach
  public void setUp() {
    when(monolith.findById("c1")).thenReturn(local);
    when(monolith.findByArticleId("a")).thenReturn(Collections.singletonList(local));
    when(monolith.findByArticleIdWithCursor("a", page))
        .thenReturn(Collections.singletonList(local));
    when(remote.findById("c1")).thenReturn(extracted);
    when(remote.findByArticleId("a")).thenReturn(Collections.singletonList(extracted));
    when(remote.findByArticleIdWithCursor("a", page))
        .thenReturn(Collections.singletonList(extracted));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_route_to_the_monolith_only() {
    Assertions.assertSame(local, port.findById("c1"));
    Assertions.assertEquals(Collections.singletonList(local), port.findByArticleId("a"));
    Assertions.assertEquals(
        Collections.singletonList(local), port.findByArticleIdWithCursor("a", page));
    verifyNoInteractions(remote);
  }

  @Test
  public void modes_are_ignored_while_the_flag_is_off() {
    properties.getComment().setRead(ReadMode.EXTRACTED);
    Assertions.assertSame(local, port.findById("c1"));
    verifyNoInteractions(remote);
  }

  @Test
  public void extracted_mode_reads_from_the_service() {
    enable(ReadMode.EXTRACTED);
    Assertions.assertSame(extracted, port.findById("c1"));
    Assertions.assertEquals(Collections.singletonList(extracted), port.findByArticleId("a"));
    Assertions.assertEquals(
        Collections.singletonList(extracted), port.findByArticleIdWithCursor("a", page));
    verify(monolith, never()).findById(any());
  }

  @Test
  public void shadow_returns_the_monolith_and_counts_mismatches() {
    enable(ReadMode.SHADOW);
    Assertions.assertSame(local, port.findById("c1"));
    Assertions.assertEquals(Collections.singletonList(local), port.findByArticleId("a"));
    verify(remote).findById("c1");
    verify(remote).findByArticleId("a");
    Assertions.assertEquals(2, shadow.mismatchCount());
  }

  @Test
  public void shadow_failures_are_swallowed_and_metered() {
    enable(ReadMode.SHADOW);
    when(remote.findByArticleId("a")).thenThrow(new CommentServiceException("down", null));
    Assertions.assertEquals(Collections.singletonList(local), port.findByArticleId("a"));
    Assertions.assertEquals(1, shadow.errorCount());
  }

  @Test
  public void fallback_monolith_serves_the_local_answer_on_failure() {
    enable(ReadMode.EXTRACTED);
    when(remote.findByArticleId("a")).thenThrow(new CommentServiceException("down", null));
    Assertions.assertEquals(Collections.singletonList(local), port.findByArticleId("a"));
  }

  @Test
  public void fallback_empty_serves_safe_defaults() {
    enable(ReadMode.EXTRACTED);
    properties.getComment().setFallback(Fallback.EMPTY);
    CommentServiceException down = new CommentServiceException("down", null);
    when(remote.findById("c1")).thenThrow(down);
    when(remote.findByArticleId("a")).thenThrow(down);
    when(remote.findByArticleIdWithCursor("a", page)).thenThrow(down);

    Assertions.assertNull(port.findById("c1"));
    Assertions.assertEquals(Collections.emptyList(), port.findByArticleId("a"));
    Assertions.assertEquals(Collections.emptyList(), port.findByArticleIdWithCursor("a", page));
    verify(monolith, never()).findByArticleId(any());
  }

  @Test
  public void fallback_fail_rethrows() {
    enable(ReadMode.EXTRACTED);
    properties.getComment().setFallback(Fallback.FAIL);
    when(remote.findByArticleId("a")).thenThrow(new CommentServiceException("down", null));
    Assertions.assertThrows(CommentServiceException.class, () -> port.findByArticleId("a"));
  }

  @Test
  public void read_after_write_is_served_locally_while_the_monolith_is_authoritative() {
    enable(ReadMode.EXTRACTED);
    properties.getComment().setWrite(WriteMode.DUAL_WRITE);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten(RoutingCommentQueryPort.DOMAIN);

    Assertions.assertSame(local, port.findById("c1"));
    Assertions.assertEquals(Collections.singletonList(local), port.findByArticleId("a"));
    verifyNoInteractions(remote);
  }

  @Test
  public void read_after_write_goes_remote_once_the_service_owns_writes() {
    enable(ReadMode.EXTRACTED);
    properties.getComment().setWrite(WriteMode.EXTRACTED);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten(RoutingCommentQueryPort.DOMAIN);

    Assertions.assertSame(extracted, port.findById("c1"));
  }

  private void enable(ReadMode mode) {
    properties.getComment().setEnabled(true);
    properties.getComment().setRead(mode);
  }
}
