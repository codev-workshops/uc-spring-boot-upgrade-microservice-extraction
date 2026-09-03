package io.spring.infrastructure.extraction.tag;

import io.spring.application.data.ArticleTagList;
import io.spring.application.tag.TagQueryPort;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.ShadowComparator;
import io.spring.infrastructure.mybatis.readservice.TagReadService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Picks the monolith or the remote {@link TagQueryPort} on every call according to {@code
 * extraction.tag.*}. In {@code shadow} mode the monolith answer is returned and the remote one is
 * compared in the background; in {@code extracted} mode a remote failure is handled per {@code
 * fallback}. While the monolith is still authoritative for writes, a read that follows a write in
 * the same request is always served locally so the response reflects what was just written.
 */
@Primary
@Service
public class RoutingTagQueryPort implements TagQueryPort {
  static final String DOMAIN = "tag";
  private static final Logger log = LoggerFactory.getLogger(RoutingTagQueryPort.class);

  private final TagReadService monolith;
  private final RemoteTagQueryAdapter remote;
  private final ExtractionProperties properties;
  private final ShadowComparator shadow;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingTagQueryPort(
      TagReadService monolith,
      RemoteTagQueryAdapter remote,
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
  public List<String> allTags() {
    return route("allTags", monolith::allTags, remote::allTags, ArrayList::new);
  }

  @Override
  public List<ArticleTagList> tagsByArticleIds(List<String> articleIds) {
    return route(
        "tagsByArticleIds",
        () -> monolith.tagsByArticleIds(articleIds),
        () -> remote.tagsByArticleIds(articleIds),
        () ->
            articleIds.stream()
                .map(id -> new ArticleTagList(id, new ArrayList<>()))
                .collect(Collectors.toList()));
  }

  @Override
  public List<String> articleIdsByTag(String tagName) {
    return route(
        "articleIdsByTag",
        () -> monolith.articleIdsByTag(tagName),
        () -> remote.articleIdsByTag(tagName),
        ArrayList::new);
  }

  /**
   * The SQL joins in {@code ArticleReadService.xml} stay in charge unless the flag is on and reads
   * are {@code extracted} or {@code shadow}; only then does {@code ArticleQueryService} resolve
   * {@code tagList} and the {@code tag=} filter through this port.
   */
  @Override
  public boolean ownsTagReads() {
    DomainRoute route = properties.getTag();
    return route.readsRemote() || route.shadows();
  }

  private <T> T route(String op, Supplier<T> local, Supplier<T> extracted, Supplier<T> empty) {
    DomainRoute route = properties.getTag();
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
    } catch (ArticleServiceException e) {
      return fallback(route, op, local, empty, e);
    }
  }

  private <T> T fallback(
      DomainRoute route,
      String op,
      Supplier<T> local,
      Supplier<T> empty,
      ArticleServiceException cause) {
    log.warn(
        "article-service tag read failed op={} fallback={} cause={}",
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
