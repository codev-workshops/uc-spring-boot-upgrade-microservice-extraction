package io.spring.article.application;

import io.spring.article.application.data.ArticleTagRow;
import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.infrastructure.mybatis.readservice.TagReadService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TagQueryService {
  private final TagReadService tagReadService;

  public TagQueryService(TagReadService tagReadService) {
    this.tagReadService = tagReadService;
  }

  /** All tag names in `select name from tags` order (duplicates included if present). */
  public List<String> allTags() {
    return tagReadService.all();
  }

  /**
   * One entry per requested id, in request order, with an empty tagList for ids without tags.
   * Duplicate ids in the request collapse to one entry.
   */
  public List<ArticleTagsData> findArticleTags(List<String> articleIds) {
    Map<String, List<String>> byArticle = new LinkedHashMap<>();
    for (String id : articleIds) {
      byArticle.putIfAbsent(id, new ArrayList<>());
    }
    if (byArticle.isEmpty()) {
      return new ArrayList<>();
    }
    for (ArticleTagRow row : tagReadService.findArticleTags(new ArrayList<>(byArticle.keySet()))) {
      byArticle.get(row.getArticleId()).add(row.getTagName());
    }
    return byArticle.entrySet().stream()
        .map(e -> new ArticleTagsData(e.getKey(), e.getValue()))
        .collect(Collectors.toList());
  }

  /** Distinct article ids tagged with the name in article_tags rowid order; unknown tag -> []. */
  public List<String> findArticleIdsByTagName(String name) {
    return tagReadService.findArticleIdsByTagName(name);
  }
}
