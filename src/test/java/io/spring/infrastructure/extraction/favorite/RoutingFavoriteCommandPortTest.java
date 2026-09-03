package io.spring.infrastructure.extraction.favorite;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.core.favorite.ArticleFavorite;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.repository.MyBatisArticleFavoriteRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class RoutingFavoriteCommandPortTest {
  private final LocalFavoriteCommand local = mock(LocalFavoriteCommand.class);
  private final DualWriteFavoriteCommand dualWrite = mock(DualWriteFavoriteCommand.class);
  private final RemoteFavoriteCommand remote = mock(RemoteFavoriteCommand.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();
  private final RoutingFavoriteCommandPort port =
      new RoutingFavoriteCommandPort(local, dualWrite, remote, properties, marker);

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
    port.favorite("a", "u");
    verify(local).favorite("a", "u");
    verifyNoInteractions(dualWrite, remote);
    Assertions.assertTrue(marker.writtenInThisRequest("favorite"));
  }

  @Test
  public void write_mode_is_ignored_while_the_flag_is_off() {
    properties.getFavorite().setWrite(WriteMode.EXTRACTED);
    port.unfavorite("a", "u");
    verify(local).unfavorite("a", "u");
    verifyNoInteractions(remote);
  }

  @Test
  public void dual_write_and_extracted_select_their_command() {
    properties.getFavorite().setEnabled(true);
    properties.getFavorite().setWrite(WriteMode.DUAL_WRITE);
    port.favorite("a", "u");
    verify(dualWrite).favorite("a", "u");

    properties.getFavorite().setWrite(WriteMode.EXTRACTED);
    port.unfavorite("a", "u");
    verify(remote).unfavorite("a", "u");
    verifyNoInteractions(local);
  }

  @Test
  public void routing_repository_finds_locally_while_authoritative_then_remotely() {
    MyBatisArticleFavoriteRepository monolith = mock(MyBatisArticleFavoriteRepository.class);
    RoutingFavoriteQueryPort queries = mock(RoutingFavoriteQueryPort.class);
    RoutingArticleFavoriteRepository repository =
        new RoutingArticleFavoriteRepository(monolith, port, queries, properties);
    when(monolith.find("a", "u")).thenReturn(Optional.of(new ArticleFavorite("a", "u")));
    when(queries.isUserFavorite("u", "a")).thenReturn(false);

    Assertions.assertTrue(repository.find("a", "u").isPresent());
    repository.save(new ArticleFavorite("a", "u"));
    verify(local).favorite("a", "u");

    properties.getFavorite().setEnabled(true);
    properties.getFavorite().setWrite(WriteMode.EXTRACTED);
    Assertions.assertFalse(repository.find("a", "u").isPresent());
    repository.remove(new ArticleFavorite("a", "u"));
    verify(remote).unfavorite("a", "u");
  }
}
