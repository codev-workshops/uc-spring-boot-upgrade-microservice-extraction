package io.spring.application.tag;

import io.spring.core.article.Tag;
import java.util.Collection;

/**
 * Write side of the Tag domain. Tags are only ever written as part of an article create ({@code
 * MyBatisArticleRepository.createNew}); the monolith generates the tag ids so every store receives
 * identical {@code tags} rows. The operation is an idempotent "set" of the article's tag list.
 */
public interface TagCommandPort {
  void setTags(String articleId, Collection<Tag> tags);
}
