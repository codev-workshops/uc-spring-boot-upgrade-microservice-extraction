package io.spring.infrastructure.extraction.favorite;

import io.spring.application.favorite.FavoriteCommandPort;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Selects the local, dual-write or remote {@link FavoriteCommandPort} per call from {@code
 * extraction.favorite.write} and marks the request so a following read is served by the
 * authoritative store.
 */
@Primary
@Service
public class RoutingFavoriteCommandPort implements FavoriteCommandPort {
  private final LocalFavoriteCommand local;
  private final DualWriteFavoriteCommand dualWrite;
  private final RemoteFavoriteCommand remote;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingFavoriteCommandPort(
      LocalFavoriteCommand local,
      DualWriteFavoriteCommand dualWrite,
      RemoteFavoriteCommand remote,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite) {
    this.local = local;
    this.dualWrite = dualWrite;
    this.remote = remote;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public void favorite(String articleId, String userId) {
    select().favorite(articleId, userId);
    readAfterWrite.markWritten(RoutingFavoriteQueryPort.DOMAIN);
  }

  @Override
  public void unfavorite(String articleId, String userId) {
    select().unfavorite(articleId, userId);
    readAfterWrite.markWritten(RoutingFavoriteQueryPort.DOMAIN);
  }

  FavoriteCommandPort select() {
    DomainRoute route = properties.getFavorite();
    if (!route.isEnabled()) {
      return local;
    }
    switch (route.getWrite()) {
      case DUAL_WRITE:
        return dualWrite;
      case EXTRACTED:
        return remote;
      case MONOLITH:
      default:
        return local;
    }
  }
}
