package io.spring.infrastructure.extraction.tag;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Tag;
import java.util.Collection;
import org.springframework.stereotype.Component;

/** Writes to article-service; failures propagate as {@link ArticleServiceException}. */
@Component
public class RemoteTagCommand implements TagCommandPort {
  private final ArticleServiceClient client;

  public RemoteTagCommand(ArticleServiceClient client) {
    this.client = client;
  }

  @Override
  public void setTags(String articleId, Collection<Tag> tags) {
    client.setTags(articleId, tags);
  }
}
