package io.spring.application.tag;

import io.spring.application.data.ArticleTagList;
import io.spring.core.article.Article;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.mybatis.readservice.TagReadService;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** The MyBatis {@link TagQueryPort} answers exactly what the SQL joins in the monolith answer. */
@Import(MyBatisArticleRepository.class)
public class TagReadServicePortTest extends DbTestBase {
  @Autowired private TagReadService tagReadService;
  @Autowired private MyBatisArticleRepository articleRepository;

  @Test
  public void all_tags_matches_the_legacy_statement_and_sql_is_not_owned() {
    articleRepository.save(new Article("a", "d", "b", Arrays.asList("java", "spring"), "u1"));
    Assertions.assertEquals(tagReadService.all(), tagReadService.allTags());
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("java", "spring")), new HashSet<>(tagReadService.allTags()));
    Assertions.assertFalse(tagReadService.ownsTagReads());
  }

  @Test
  public void tags_by_article_ids_returns_one_entry_per_id_in_request_order() {
    Article tagged = new Article("a", "d", "b", Arrays.asList("java", "spring"), "u1");
    Article bare = new Article("c", "d", "b", Collections.emptyList(), "u1");
    articleRepository.save(tagged);
    articleRepository.save(bare);

    List<ArticleTagList> lists =
        tagReadService.tagsByArticleIds(Arrays.asList(bare.getId(), tagged.getId(), "missing"));

    Assertions.assertEquals(3, lists.size());
    Assertions.assertEquals(bare.getId(), lists.get(0).getArticleId());
    Assertions.assertTrue(lists.get(0).getTagList().isEmpty());
    Assertions.assertEquals(tagged.getId(), lists.get(1).getArticleId());
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("java", "spring")), new HashSet<>(lists.get(1).getTagList()));
    Assertions.assertTrue(lists.get(2).getTagList().isEmpty());
    Assertions.assertTrue(tagReadService.tagsByArticleIds(new ArrayList<>()).isEmpty());
  }

  @Test
  public void article_ids_by_tag_is_distinct_and_empty_for_an_unknown_tag() {
    Article first = new Article("a", "d", "b", Arrays.asList("java", "java", "spring"), "u1");
    Article second = new Article("c", "d", "b", Collections.singletonList("java"), "u1");
    articleRepository.save(first);
    articleRepository.save(second);

    Assertions.assertEquals(
        new HashSet<>(Arrays.asList(first.getId(), second.getId())),
        new HashSet<>(tagReadService.articleIdsByTag("java")));
    Assertions.assertEquals(2, tagReadService.articleIdsByTag("java").size());
    Assertions.assertEquals(
        Collections.singletonList(first.getId()), tagReadService.articleIdsByTag("spring"));
    Assertions.assertTrue(tagReadService.articleIdsByTag("nope").isEmpty());
  }
}
