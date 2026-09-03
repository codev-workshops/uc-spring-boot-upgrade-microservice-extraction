package io.spring.infrastructure.extraction.article;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager.Direction;
import io.spring.application.Page;
import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleRow;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.LoggingShadowComparator;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.tag.ArticleServiceException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingArticleQueryPortTest {
  private final LocalArticleQueryAdapter monolith = mock(LocalArticleQueryAdapter.class);
  private final RemoteArticleQueryAdapter remote = mock(RemoteArticleQueryAdapter.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final LoggingShadowComparator shadow = new LoggingShadowComparator(Runnable::run);
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingArticleQueryPort port =
      new RoutingArticleQueryPort(monolith, remote, properties, shadow, marker);

  private final ArticleRow localRow = row("a", "local");
  private final ArticleRow remoteRow = row("a", "remote");
  private final Page page = new Page(0, 20);
  private final CursorPageParameter<DateTime> cursor =
      new CursorPageParameter<>(null, 20, Direction.NEXT);
  private final List<String> authors = Collections.singletonList("u1");

  @BeforeEach
  public void setUp() {
    when(monolith.findById("a")).thenReturn(Optional.of(localRow));
    when(monolith.findBySlug("local")).thenReturn(Optional.of(localRow));
    when(monolith.findArticles(authors)).thenReturn(Collections.singletonList(localRow));
    when(monolith.queryArticleIds("java", null, null, page))
        .thenReturn(new ArticleIdPage(Collections.singletonList("a"), 1));
    when(monolith.queryArticleIdsWithCursor("java", null, null, cursor))
        .thenReturn(Collections.singletonList("a"));
    when(monolith.findArticlesOfAuthors(authors, page))
        .thenReturn(new ArticleRowPage(Collections.singletonList(localRow), 1));
    when(monolith.findArticlesOfAuthorsWithCursor(authors, cursor))
        .thenReturn(Collections.singletonList(localRow));

    when(remote.findById("a")).thenReturn(Optional.of(remoteRow));
    when(remote.findBySlug("local")).thenReturn(Optional.of(remoteRow));
    when(remote.findArticles(authors)).thenReturn(Collections.singletonList(remoteRow));
    when(remote.queryArticleIds("java", null, null, page))
        .thenReturn(new ArticleIdPage(Arrays.asList("a", "b"), 2));
    when(remote.queryArticleIdsWithCursor("java", null, null, cursor))
        .thenReturn(Arrays.asList("a", "b"));
    when(remote.findArticlesOfAuthors(authors, page))
        .thenReturn(new ArticleRowPage(Collections.singletonList(remoteRow), 1));
    when(remote.findArticlesOfAuthorsWithCursor(authors, cursor))
        .thenReturn(Collections.singletonList(remoteRow));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_route_everything_to_the_monolith_and_do_not_own_reads() {
    Assertions.assertSame(localRow, port.findById("a").get());
    Assertions.assertSame(localRow, port.findBySlug("local").get());
    Assertions.assertEquals(1, port.findArticles(authors).size());
    Assertions.assertEquals(1, port.queryArticleIds("java", null, null, page).getCount());
    Assertions.assertEquals(1, port.queryArticleIdsWithCursor("java", null, null, cursor).size());
    Assertions.assertEquals(1, port.findArticlesOfAuthors(authors, page).getCount());
    Assertions.assertEquals(1, port.findArticlesOfAuthorsWithCursor(authors, cursor).size());
    Assertions.assertFalse(port.ownsArticleReads());
    verifyNoInteractions(remote);
  }

  @Test
  public void modes_are_ignored_while_the_flag_is_off() {
    properties.getArticle().setRead(ReadMode.EXTRACTED);
    Assertions.assertSame(localRow, port.findBySlug("local").get());
    Assertions.assertFalse(port.ownsArticleReads());
    verifyNoInteractions(remote);
  }

  @Test
  public void extracted_reads_come_from_the_service_and_the_port_owns_reads() {
    extracted();
    Assertions.assertSame(remoteRow, port.findById("a").get());
    Assertions.assertSame(remoteRow, port.findBySlug("local").get());
    Assertions.assertEquals(2, port.queryArticleIds("java", null, null, page).getCount());
    Assertions.assertEquals(2, port.queryArticleIdsWithCursor("java", null, null, cursor).size());
    Assertions.assertSame(
        remoteRow, port.findArticlesOfAuthors(authors, page).getArticles().get(0));
    Assertions.assertSame(remoteRow, port.findArticlesOfAuthorsWithCursor(authors, cursor).get(0));
    Assertions.assertSame(remoteRow, port.findArticles(authors).get(0));
    Assertions.assertTrue(port.ownsArticleReads());
    verifyNoInteractions(monolith);
  }

  @Test
  public void shadow_returns_the_monolith_value_and_compares_the_remote_one() {
    properties.getArticle().setEnabled(true);
    properties.getArticle().setRead(ReadMode.SHADOW);
    Assertions.assertSame(localRow, port.findBySlug("local").get());
    Assertions.assertEquals(1, port.queryArticleIds("java", null, null, page).getCount());
    Assertions.assertTrue(port.ownsArticleReads());
    verify(remote).findBySlug("local");
    verify(remote).queryArticleIds("java", null, null, page);
  }

  @Test
  public void fallback_monolith_uses_local_data_when_the_service_fails() {
    extracted();
    when(remote.findBySlug("local")).thenThrow(new ArticleServiceException("down", null));
    when(remote.findArticlesOfAuthors(authors, page))
        .thenThrow(new ArticleServiceException("down", null));
    Assertions.assertSame(localRow, port.findBySlug("local").get());
    Assertions.assertEquals(1, port.findArticlesOfAuthors(authors, page).getCount());
  }

  @Test
  public void fallback_empty_returns_empty_results() {
    extracted();
    properties.getArticle().setFallback(Fallback.EMPTY);
    when(remote.findBySlug("local")).thenThrow(new ArticleServiceException("down", null));
    when(remote.findArticles(authors)).thenThrow(new ArticleServiceException("down", null));
    when(remote.queryArticleIds("java", null, null, page))
        .thenThrow(new ArticleServiceException("down", null));
    when(remote.queryArticleIdsWithCursor("java", null, null, cursor))
        .thenThrow(new ArticleServiceException("down", null));
    when(remote.findArticlesOfAuthors(authors, page))
        .thenThrow(new ArticleServiceException("down", null));
    when(remote.findArticlesOfAuthorsWithCursor(authors, cursor))
        .thenThrow(new ArticleServiceException("down", null));

    Assertions.assertFalse(port.findBySlug("local").isPresent());
    Assertions.assertTrue(port.findArticles(authors).isEmpty());
    Assertions.assertEquals(0, port.queryArticleIds("java", null, null, page).getCount());
    Assertions.assertTrue(port.queryArticleIdsWithCursor("java", null, null, cursor).isEmpty());
    Assertions.assertEquals(0, port.findArticlesOfAuthors(authors, page).getCount());
    Assertions.assertTrue(port.findArticlesOfAuthorsWithCursor(authors, cursor).isEmpty());
    verifyNoInteractions(monolith);
  }

  @Test
  public void fallback_fail_rethrows_the_domain_exception() {
    extracted();
    properties.getArticle().setFallback(Fallback.FAIL);
    when(remote.findById("a")).thenThrow(new ArticleServiceException("down", null));
    Assertions.assertThrows(ArticleServiceException.class, () -> port.findById("a"));
    verifyNoInteractions(monolith);
  }

  @Test
  public void
      reads_after_a_write_in_the_same_request_stay_local_while_the_monolith_is_authoritative() {
    extracted();
    properties.getArticle().setWrite(WriteMode.DUAL_WRITE);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten(RoutingArticleQueryPort.DOMAIN);

    Assertions.assertSame(localRow, port.findBySlug("local").get());
    verify(remote, never()).findBySlug("local");

    properties.getArticle().setWrite(WriteMode.EXTRACTED);
    Assertions.assertSame(remoteRow, port.findBySlug("local").get());
  }

  private void extracted() {
    properties.getArticle().setEnabled(true);
    properties.getArticle().setRead(ReadMode.EXTRACTED);
  }

  private static ArticleRow row(String id, String slug) {
    return new ArticleRow(
        id, slug, slug, "d", "b", "u1", new DateTime(0), new DateTime(0), Collections.emptyList());
  }
}
