package io.spring.infrastructure.mybatis.readservice;

import io.spring.application.data.ArticleTagList;
import io.spring.application.tag.TagQueryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Monolith-table implementation of {@link TagQueryPort} (the MyBatis adapter). */
@Mapper
public interface TagReadService extends TagQueryPort {
  List<String> all();

  @Override
  default List<String> allTags() {
    return all();
  }

  /** Flat {@code (article_id, name)} rows in {@code article_tags} rowid order. */
  List<ArticleTagRow> articleTagRows(@Param("articleIds") List<String> articleIds);

  @Override
  default List<ArticleTagList> tagsByArticleIds(List<String> articleIds) {
    Map<String, List<String>> byArticle = new LinkedHashMap<>();
    for (String id : articleIds) {
      byArticle.putIfAbsent(id, new ArrayList<>());
    }
    if (!byArticle.isEmpty()) {
      for (ArticleTagRow row : articleTagRows(new ArrayList<>(byArticle.keySet()))) {
        byArticle.get(row.getArticleId()).add(row.getName());
      }
    }
    List<ArticleTagList> result = new ArrayList<>(byArticle.size());
    byArticle.forEach((id, tags) -> result.add(new ArticleTagList(id, tags)));
    return result;
  }

  @Override
  List<String> articleIdsByTag(@Param("tagName") String tagName);
}
