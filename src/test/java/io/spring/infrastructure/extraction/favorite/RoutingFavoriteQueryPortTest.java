package io.spring.infrastructure.extraction.favorite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.LoggingShadowComparator;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingFavoriteQueryPortTest {
  private final ArticleFavoritesReadService monolith = mock(ArticleFavoritesReadService.class);
  private final RemoteFavoriteQueryAdapter remote = mock(RemoteFavoriteQueryAdapter.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final LoggingShadowComparator shadow = new LoggingShadowComparator(Runnable::run);
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingFavoriteQueryPort port =
      new RoutingFavoriteQueryPort(monolith, remote, properties, shadow, marker);
  private final User user = new User("reader@test.com", "reader", "123", "", "");
  private final List<String> ids = Arrays.asList("a", "b");

  @BeforeEach
  public void setUp() {
    when(monolith.articleFavoriteCount("a")).thenReturn(1);
    when(monolith.articlesFavoriteCount(ids)).thenReturn(counts(1, 0));
    when(remote.articleFavoriteCount("a")).thenReturn(2);
    when(remote.articlesFavoriteCount(ids)).thenReturn(counts(2, 0));
  }

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void defaults_route_to_the_monolith_only() {
    Assertions.assertEquals(1, port.articleFavoriteCount("a"));
    Assertions.assertEquals(counts(1, 0), port.articlesFavoriteCount(ids));
    Assertions.assertFalse(port.ownsFavoritedByFilter());
    verifyNoInteractions(remote);
  }

  @Test
  public void modes_are_ignored_while_the_flag_is_off() {
    properties.getFavorite().setRead(ReadMode.EXTRACTED);
    Assertions.assertEquals(1, port.articleFavoriteCount("a"));
    verifyNoInteractions(remote);
  }

  @Test
  public void extracted_reads_go_to_the_remote_adapter() {
    enable(ReadMode.EXTRACTED);
    Assertions.assertEquals(2, port.articleFavoriteCount("a"));
    Assertions.assertEquals(counts(2, 0), port.articlesFavoriteCount(ids));
    Assertions.assertTrue(port.ownsFavoritedByFilter());
    verify(monolith, never()).articleFavoriteCount(any());
  }

  @Test
  public void empty_batches_short_circuit_before_routing() {
    enable(ReadMode.EXTRACTED);
    Assertions.assertTrue(port.articlesFavoriteCount(new ArrayList<>()).isEmpty());
    Assertions.assertTrue(port.userFavorites(new ArrayList<>(), user).isEmpty());
    verifyNoInteractions(remote);
    verifyNoInteractions(monolith);
  }

  @Test
  public void shadow_returns_the_monolith_result_and_meters_mismatches() {
    enable(ReadMode.SHADOW);
    Assertions.assertEquals(1, port.articleFavoriteCount("a"));
    Assertions.assertEquals(counts(1, 0), port.articlesFavoriteCount(ids));
    verify(remote).articleFavoriteCount("a");
    Assertions.assertEquals(2, shadow.mismatchCount());
    Assertions.assertFalse(port.ownsFavoritedByFilter());
  }

  @Test
  public void shadow_survives_a_remote_failure() {
    enable(ReadMode.SHADOW);
    when(remote.articleFavoriteCount("a")).thenThrow(new FavoriteServiceException("down", null));
    Assertions.assertEquals(1, port.articleFavoriteCount("a"));
    Assertions.assertEquals(1, shadow.errorCount());
  }

  @Test
  public void fallback_monolith_serves_the_local_result_on_remote_failure() {
    enable(ReadMode.EXTRACTED);
    when(remote.articlesFavoriteCount(ids)).thenThrow(new FavoriteServiceException("down", null));
    Assertions.assertEquals(counts(1, 0), port.articlesFavoriteCount(ids));
  }

  @Test
  public void fallback_empty_serves_zero_filled_safe_values() {
    enable(ReadMode.EXTRACTED);
    properties.getFavorite().setFallback(Fallback.EMPTY);
    FavoriteServiceException down = new FavoriteServiceException("down", null);
    when(remote.articlesFavoriteCount(ids)).thenThrow(down);
    when(remote.userFavorites(eq(ids), any())).thenThrow(down);
    when(remote.isUserFavorite(any(), any())).thenThrow(down);
    when(remote.articleFavoriteCount("a")).thenThrow(down);
    when(remote.articleIdsFavoritedBy(any())).thenThrow(down);

    Assertions.assertEquals(counts(0, 0), port.articlesFavoriteCount(ids));
    Assertions.assertEquals(new HashSet<>(), port.userFavorites(ids, user));
    Assertions.assertFalse(port.isUserFavorite(user.getId(), "a"));
    Assertions.assertEquals(0, port.articleFavoriteCount("a"));
    Assertions.assertEquals(Collections.emptyList(), port.articleIdsFavoritedBy(user.getId()));
    verify(monolith, never()).articlesFavoriteCount(anyList());
  }

  @Test
  public void fallback_fail_propagates_the_exception() {
    enable(ReadMode.EXTRACTED);
    properties.getFavorite().setFallback(Fallback.FAIL);
    when(remote.articleFavoriteCount("a")).thenThrow(new FavoriteServiceException("down", null));
    Assertions.assertThrows(FavoriteServiceException.class, () -> port.articleFavoriteCount("a"));
  }

  @Test
  public void read_after_write_is_served_locally_while_the_monolith_is_authoritative() {
    enable(ReadMode.EXTRACTED);
    properties.getFavorite().setWrite(WriteMode.DUAL_WRITE);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten("favorite");

    Assertions.assertEquals(1, port.articleFavoriteCount("a"));
    verifyNoInteractions(remote);
  }

  @Test
  public void read_after_write_follows_the_remote_once_writes_are_extracted() {
    enable(ReadMode.EXTRACTED);
    properties.getFavorite().setWrite(WriteMode.EXTRACTED);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    marker.markWritten("favorite");

    Assertions.assertEquals(2, port.articleFavoriteCount("a"));
  }

  private void enable(ReadMode mode) {
    properties.getFavorite().setEnabled(true);
    properties.getFavorite().setRead(mode);
  }

  private static List<ArticleFavoriteCount> counts(int a, int b) {
    return Arrays.asList(new ArticleFavoriteCount("a", a), new ArticleFavoriteCount("b", b));
  }
}
