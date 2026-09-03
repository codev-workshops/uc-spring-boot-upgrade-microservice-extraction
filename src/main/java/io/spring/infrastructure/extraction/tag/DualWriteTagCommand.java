package io.spring.infrastructure.extraction.tag;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Monolith-first dual write. The monolith copy of the tags is written by {@code
 * MyBatisArticleRepository.save} inside the article's own transaction (the article row stays in the
 * monolith in Phase 3), so this adapter only mirrors the committed tag set to article-service; any
 * remote failure is swallowed, logged and queued in {@link #pendingMirrorOperations()} for
 * reconciliation.
 */
@Component
public class DualWriteTagCommand implements TagCommandPort {
  private static final Logger log = LoggerFactory.getLogger(DualWriteTagCommand.class);

  private final TagCommandPort remote;
  private final ConcurrentLinkedQueue<PendingTagMirrorOperation> pending =
      new ConcurrentLinkedQueue<>();

  @Autowired
  public DualWriteTagCommand(RemoteTagCommand remote) {
    this((TagCommandPort) remote);
  }

  DualWriteTagCommand(TagCommandPort remote) {
    this.remote = remote;
  }

  @Override
  public void setTags(String articleId, Collection<Tag> tags) {
    try {
      remote.setTags(articleId, tags);
    } catch (RuntimeException e) {
      List<String> names = tags.stream().map(Tag::getName).collect(Collectors.toList());
      pending.add(new PendingTagMirrorOperation(articleId, names, Instant.now(), e.getMessage()));
      log.warn(
          "tag mirror failed articleId={} tags={} pending={} cause={}",
          articleId,
          names,
          pending.size(),
          e.getMessage());
    }
  }

  /** Snapshot of tag sets still to be replayed against article-service. */
  public List<PendingTagMirrorOperation> pendingMirrorOperations() {
    return new ArrayList<>(pending);
  }

  public void clearPending() {
    pending.clear();
  }
}
