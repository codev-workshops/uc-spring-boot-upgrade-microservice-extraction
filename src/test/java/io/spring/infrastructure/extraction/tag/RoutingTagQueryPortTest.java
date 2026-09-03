package io.spring.infrastructure.extraction.tag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.data.ArticleTagList;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.LoggingShadowComparator;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.mybatis.readservice.TagReadService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingTagQueryPortTest {
  private final TagReadService monolith = mock(TagReadService.class);
  private final RemoteTagQueryAdapter remote = mock(RemoteTagQueryAdapter.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final LoggingShadowComparator shadow = new LoggingShadowComparator(Runnable::run);
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingTagQueryPort port =
      new RoutingTagQueryPort(monolith, remote, properties, shadow, marker);

  private final List<String> local = Arrays.asList("java", "spring");
  private final List<String> extracted = Arrays.asList("spring", "java");
  private final List<String> ids = Arrays.asList("a", "b");
  private final List<ArticleTagList> localLists =
      Arrays.asList(
          new ArticleTagList("a", local), new ArticleTagList("b", Collections.emptyList()));
  private final List<ArticleTagList> extractedLists =
      Arrays.asList(
          new ArticleTagList("a", extracted), new ArticleTagList("b", Collections.emptyList()));

  @BeforeEach
  public void setUp() {
    when(monolith.allTags()).thenReturn(local);
    when(monolith.tagsByArticleIds(ids)).thenReturn(localLists);
    when(monolith.articleIdsByTag("java")).thenReturn(Collections.singletonList("a"));
    when(remote.allTags()).thenReturn(extracted);
    when(remote.tagsByArticleIds(ids)).thenReturn(extractedLists);
    when(remote.articleIdsByTag("java")).thenReturn(Collections.singletonList("b"));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_route_to_the_monolith_only_and_leave_sql_in_charge() {
    Assertions.assertSame(local, port.allTags());
    Assertions.assertSame(localLists, port.tagsByArticleIds(ids));
    Assertions.assertEquals(Collections.singletonList("a"), port.articleIdsByTag("java"));
    Assertions.assertFalse(port.ownsTagReads());
    verifyNoInteractions(remote);
  }

  @Test
  public void modes_are_ignored_while_the_flag_is_off() {
    properties.getTag().setRead(ReadMode.EXTRACTED);
    Assertions.assertSame(local, port.allTags());
    Assertions.assertFalse(port.ownsTagReads());
    verifyNoInteractions(remote);
  }

  @Test
  public void extracted_mode_reads_from_the_service_and_owns_tag_reads() {
    enable(ReadMode.EXTRACTED);
    Assertions.assertSame(extracted, port.allTags());
    Assertions.assertSame(extractedLists, port.tagsByArticleIds(ids));
    Assertions.assertEquals(Collections.singletonList("b"), port.articleIdsByTag("java"));
    Assertions.assertTrue(port.ownsTagReads());
    verify(monolith, never()).allTags();
  }

  @Test
  public void shadow_mode_returns_the_monolith_and_compares_in_the_background() {
    enable(ReadMode.SHADOW);
    Assertions.assertSame(local, port.allTags());
    Assertions.assertSame(localLists, port.tagsByArticleIds(ids));
    Assertions.assertTrue(port.ownsTagReads());
    verify(remote).allTags();
    verify(remote).tagsByArticleIds(ids);
  }

  @Test
  public void shadow_mode_swallows_remote_failures() {
    enable(ReadMode.SHADOW);
    when(remote.allTags()).thenThrow(new ArticleServiceException("down", null));
    Assertions.assertSame(local, port.allTags());
  }

  @Test
  public void extracted_failure_falls_back_to_the_monolith_by_default() {
    enable(ReadMode.EXTRACTED);
    when(remote.allTags()).thenThrow(new ArticleServiceException("down", null));
    Assertions.assertSame(local, port.allTags());
  }

  @Test
  public void extracted_failure_can_fall_back_to_empty() {
    enable(ReadMode.EXTRACTED);
    properties.getTag().setFallback(Fallback.EMPTY);
    when(remote.allTags()).thenThrow(new ArticleServiceException("down", null));
    when(remote.tagsByArticleIds(anyList())).thenThrow(new ArticleServiceException("down", null));
    when(remote.articleIdsByTag(any())).thenThrow(new ArticleServiceException("down", null));

    Assertions.assertTrue(port.allTags().isEmpty());
    Assertions.assertTrue(port.articleIdsByTag("java").isEmpty());
    List<ArticleTagList> lists = port.tagsByArticleIds(ids);
    Assertions.assertEquals(2, lists.size());
    Assertions.assertEquals("a", lists.get(0).getArticleId());
    Assertions.assertTrue(lists.get(0).getTagList().isEmpty());
    verify(monolith, never()).allTags();
  }

  @Test
  public void extracted_failure_can_fail() {
    enable(ReadMode.EXTRACTED);
    properties.getTag().setFallback(Fallback.FAIL);
    when(remote.allTags()).thenThrow(new ArticleServiceException("down", null));
    Assertions.assertThrows(ArticleServiceException.class, port::allTags);
  }

  @Test
  public void
      read_after_write_in_the_same_request_is_served_locally_while_monolith_is_authoritative() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    enable(ReadMode.EXTRACTED);
    properties.getTag().setWrite(WriteMode.DUAL_WRITE);
    marker.markWritten(RoutingTagQueryPort.DOMAIN);

    Assertions.assertSame(local, port.allTags());
    Assertions.assertSame(localLists, port.tagsByArticleIds(ids));
    verifyNoInteractions(remote);
  }

  @Test
  public void read_after_write_goes_remote_once_the_service_is_authoritative() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    enable(ReadMode.EXTRACTED);
    properties.getTag().setWrite(WriteMode.EXTRACTED);
    marker.markWritten(RoutingTagQueryPort.DOMAIN);

    Assertions.assertSame(extracted, port.allTags());
  }

  private void enable(ReadMode mode) {
    properties.getTag().setEnabled(true);
    properties.getTag().setRead(mode);
  }
}
