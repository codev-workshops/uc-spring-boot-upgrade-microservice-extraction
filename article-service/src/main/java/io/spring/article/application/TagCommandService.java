package io.spring.article.application;

import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagCommandService {
  private final TagRepository tagRepository;

  public TagCommandService(TagRepository tagRepository) {
    this.tagRepository = tagRepository;
  }

  /**
   * Idempotent mirror of MyBatisArticleRepository.createNew: for each tag reuse the existing row by
   * name, else insert it with the caller-supplied id; insert the (article_id, tag_id) pair only if
   * absent. Relations are never deleted.
   */
  @Transactional
  public ArticleTagsData setTags(String articleId, List<Tag> tags) {
    for (Tag tag : tags) {
      Optional<Tag> existing = tagRepository.findByName(tag.getName());
      Tag stored;
      if (existing.isPresent()) {
        stored = existing.get();
      } else {
        tagRepository.insert(tag);
        stored = tag;
      }
      if (!tagRepository.relationExists(articleId, stored.getId())) {
        tagRepository.insertRelation(articleId, stored.getId());
      }
    }
    return new ArticleTagsData(articleId, tagRepository.findTagNames(articleId));
  }
}
