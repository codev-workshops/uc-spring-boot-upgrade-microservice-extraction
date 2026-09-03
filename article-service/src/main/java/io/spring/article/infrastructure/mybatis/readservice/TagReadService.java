package io.spring.article.infrastructure.mybatis.readservice;

import io.spring.article.application.data.ArticleTagRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TagReadService {
  /** Same statement as the monolith's TagReadService.all(): no ORDER BY, no DISTINCT. */
  List<String> all();

  /** (article_id, name) pairs for the given articles in article_tags rowid order. */
  List<ArticleTagRow> findArticleTags(@Param("articleIds") List<String> articleIds);

  /** Distinct article ids tagged with the name, in article_tags rowid order. */
  List<String> findArticleIdsByTagName(@Param("name") String name);
}
