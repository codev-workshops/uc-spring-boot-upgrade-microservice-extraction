package io.spring.infrastructure.extraction.tag;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Tag;
import io.spring.infrastructure.mybatis.mapper.ArticleMapper;
import java.util.Collection;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes to the monolith {@code tags}/{@code article_tags} tables with the same statements as
 * {@code MyBatisArticleRepository.createNew}. Only used when the article row itself is not
 * persisted locally by the repository ({@code write=extracted}); with {@code monolith} and {@code
 * dual-write} the repository's own transaction already wrote the tags.
 */
@Component
public class LocalTagCommand implements TagCommandPort {
  private final ArticleMapper articleMapper;

  public LocalTagCommand(ArticleMapper articleMapper) {
    this.articleMapper = articleMapper;
  }

  @Override
  @Transactional
  public void setTags(String articleId, Collection<Tag> tags) {
    for (Tag tag : tags) {
      Tag target =
          Optional.ofNullable(articleMapper.findTag(tag.getName()))
              .orElseGet(
                  () -> {
                    articleMapper.insertTag(tag);
                    return tag;
                  });
      articleMapper.insertArticleTagRelation(articleId, target.getId());
    }
  }
}
