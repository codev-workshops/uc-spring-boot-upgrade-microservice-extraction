package io.spring.infrastructure.extraction.favorite;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.core.user.User;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.ShadowComparator;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Picks the monolith or the remote {@link FavoriteQueryPort} on every call according to {@code
 * extraction.favorite.*}. In {@code shadow} mode the monolith answer is returned and the remote one
 * is compared in the background; in {@code extracted} mode a remote failure is handled per {@code
 * fallback}. While the monolith is still authoritative for writes, a read that follows a write in
 * the same request is always served locally so the response reflects what was just written.
 */
@Primary
@Service
public class RoutingFavoriteQueryPort implements FavoriteQueryPort {
  static final String DOMAIN = "favorite";
  private static final Logger log = LoggerFactory.getLogger(RoutingFavoriteQueryPort.class);

  private final ArticleFavoritesReadService monolith;
  private final RemoteFavoriteQueryAdapter remote;
  private final ExtractionProperties properties;
  private final ShadowComparator shadow;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingFavoriteQueryPort(
      ArticleFavoritesReadService monolith,
      RemoteFavoriteQueryAdapter remote,
      ExtractionProperties properties,
      ShadowComparator shadow,
      ReadAfterWriteMarker readAfterWrite) {
    this.monolith = monolith;
    this.remote = remote;
    this.properties = properties;
    this.shadow = shadow;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public boolean isUserFavorite(String userId, String articleId) {
    return route(
        "isUserFavorite",
        () -> monolith.isUserFavorite(userId, articleId),
        () -> remote.isUserFavorite(userId, articleId),
        () -> false);
  }

  @Override
  public int articleFavoriteCount(String articleId) {
    return route(
        "articleFavoriteCount",
        () -> monolith.articleFavoriteCount(articleId),
        () -> remote.articleFavoriteCount(articleId),
        () -> 0);
  }

  @Override
  public List<ArticleFavoriteCount> articlesFavoriteCount(List<String> ids) {
    if (ids.isEmpty()) {
      return new ArrayList<>();
    }
    return route(
        "articlesFavoriteCount",
        () -> monolith.articlesFavoriteCount(ids),
        () -> remote.articlesFavoriteCount(ids),
        () -> RemoteFavoriteQueryAdapter.zeroFill(ids, new HashMap<>()));
  }

  @Override
  public Set<String> userFavorites(List<String> ids, User currentUser) {
    if (ids.isEmpty()) {
      return new HashSet<>();
    }
    return route(
        "userFavorites",
        () -> monolith.userFavorites(ids, currentUser),
        () -> remote.userFavorites(ids, currentUser),
        HashSet::new);
  }

  @Override
  public List<String> articleIdsFavoritedBy(String userId) {
    return route(
        "articleIdsFavoritedBy",
        () -> monolith.articleIdsFavoritedBy(userId),
        () -> remote.articleIdsFavoritedBy(userId),
        ArrayList::new);
  }

  @Override
  public boolean ownsFavoritedByFilter() {
    return properties.getFavorite().readsRemote();
  }

  private <T> T route(String op, Supplier<T> local, Supplier<T> extracted, Supplier<T> empty) {
    DomainRoute route = properties.getFavorite();
    if (route.shadows()) {
      T value = local.get();
      shadow.compareAsync(DOMAIN, op, value, extracted);
      return value;
    }
    if (!route.readsRemote()) {
      return local.get();
    }
    if (route.monolithAuthoritative() && readAfterWrite.writtenInThisRequest(DOMAIN)) {
      return local.get();
    }
    try {
      return extracted.get();
    } catch (FavoriteServiceException e) {
      return fallback(route, op, local, empty, e);
    }
  }

  private <T> T fallback(
      DomainRoute route,
      String op,
      Supplier<T> local,
      Supplier<T> empty,
      FavoriteServiceException cause) {
    log.warn(
        "favorite-service read failed op={} fallback={} cause={}",
        op,
        route.getFallback(),
        cause.getMessage());
    switch (route.getFallback()) {
      case MONOLITH:
        return local.get();
      case EMPTY:
        return empty.get();
      case FAIL:
      default:
        throw cause;
    }
  }
}
