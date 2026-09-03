package io.spring.infrastructure.extraction.tag;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Tag;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import java.util.Collection;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Selects the local, dual-write or remote {@link TagCommandPort} per call from {@code
 * extraction.tag.write} and marks the request so a following read is served by the authoritative
 * store.
 */
@Primary
@Service
public class RoutingTagCommandPort implements TagCommandPort {
  private final LocalTagCommand local;
  private final DualWriteTagCommand dualWrite;
  private final RemoteTagCommand remote;
  private final ExtractionProperties properties;
  private final ReadAfterWriteMarker readAfterWrite;

  public RoutingTagCommandPort(
      LocalTagCommand local,
      DualWriteTagCommand dualWrite,
      RemoteTagCommand remote,
      ExtractionProperties properties,
      ReadAfterWriteMarker readAfterWrite) {
    this.local = local;
    this.dualWrite = dualWrite;
    this.remote = remote;
    this.properties = properties;
    this.readAfterWrite = readAfterWrite;
  }

  @Override
  public void setTags(String articleId, Collection<Tag> tags) {
    select().setTags(articleId, tags);
    readAfterWrite.markWritten(RoutingTagQueryPort.DOMAIN);
  }

  TagCommandPort select() {
    DomainRoute route = properties.getTag();
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
