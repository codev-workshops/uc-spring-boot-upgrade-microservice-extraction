package io.spring.infrastructure.extraction.favorite;

import io.spring.application.favorite.FavoriteCommandPort;
import org.springframework.stereotype.Component;

/** Writes to favorite-service; failures propagate as {@link FavoriteServiceException}. */
@Component
public class RemoteFavoriteCommand implements FavoriteCommandPort {
  private final FavoriteServiceClient client;

  public RemoteFavoriteCommand(FavoriteServiceClient client) {
    this.client = client;
  }

  @Override
  public void favorite(String articleId, String userId) {
    client.favorite(articleId, userId);
  }

  @Override
  public void unfavorite(String articleId, String userId) {
    client.unfavorite(articleId, userId);
  }
}
