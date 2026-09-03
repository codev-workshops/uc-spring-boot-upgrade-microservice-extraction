package io.spring.infrastructure.extraction.tag;

import io.spring.application.data.ArticleTagList;
import io.spring.application.tag.TagQueryPort;
import io.spring.application.tag.dto.ArticleTagsRowDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link TagQueryPort} backed by article-service. Rows the service omits are filled with an empty
 * list so callers always get one entry per requested id, in request order, exactly like the MyBatis
 * adapter.
 */
@Component
public class RemoteTagQueryAdapter implements TagQueryPort {
  private final ArticleServiceClient client;

  public RemoteTagQueryAdapter(ArticleServiceClient client) {
    this.client = client;
  }

  @Override
  public List<String> allTags() {
    return client.allTags();
  }

  @Override
  public List<ArticleTagList> tagsByArticleIds(List<String> articleIds) {
    Map<String, List<String>> byArticle = new LinkedHashMap<>();
    for (String id : articleIds) {
      byArticle.putIfAbsent(id, Collections.emptyList());
    }
    if (!byArticle.isEmpty()) {
      for (ArticleTagsRowDto row : client.tagsByArticleIds(new ArrayList<>(byArticle.keySet()))) {
        if (byArticle.containsKey(row.getArticleId()) && row.getTagList() != null) {
          byArticle.put(row.getArticleId(), row.getTagList());
        }
      }
    }
    List<ArticleTagList> result = new ArrayList<>(byArticle.size());
    byArticle.forEach((id, tags) -> result.add(new ArticleTagList(id, new ArrayList<>(tags))));
    return result;
  }

  @Override
  public List<String> articleIdsByTag(String tagName) {
    return client.articleIdsByTag(tagName);
  }
}
