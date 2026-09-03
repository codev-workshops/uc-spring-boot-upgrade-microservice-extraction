package io.spring.article.infrastructure.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.article.application.TagCommandService;
import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import io.spring.article.infrastructure.DbTestBase;
import io.spring.article.infrastructure.mybatis.readservice.TagReadService;
import io.spring.article.infrastructure.repository.MyBatisTagRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({MyBatisTagRepository.class, TagCommandService.class})
public class MyBatisTagRepositoryTest extends DbTestBase {
  @Autowired private TagRepository tagRepository;
  @Autowired private TagCommandService tagCommandService;
  @Autowired private TagReadService readService;

  @Test
  public void should_insert_tag_and_relation_with_supplied_id() {
    ArticleTagsData result =
        tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-1", "java")));
    assertEquals("a-1", result.getArticleId());
    assertEquals(Arrays.asList("java"), result.getTagList());
    assertEquals("t-1", tagRepository.findByName("java").get().getId());
    assertTrue(tagRepository.relationExists("a-1", "t-1"));
  }

  @Test
  public void should_reuse_existing_tag_by_name_and_ignore_supplied_id() {
    tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-1", "java")));
    tagCommandService.setTags("a-2", Arrays.asList(new Tag("other-id", "java")));
    assertEquals(Arrays.asList("java"), readService.all());
    assertEquals("t-1", tagRepository.findByName("java").get().getId());
    assertTrue(tagRepository.relationExists("a-2", "t-1"));
    assertFalse(tagRepository.relationExists("a-2", "other-id"));
  }

  @Test
  public void should_be_idempotent_and_never_delete_relations() {
    tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-1", "java"), new Tag("t-2", "sql")));
    tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-1", "java"), new Tag("t-2", "sql")));
    ArticleTagsData result = tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-3", "go")));
    assertEquals(Arrays.asList("java", "sql", "go"), result.getTagList());
    assertEquals(Arrays.asList("java", "sql", "go"), readService.all());
    assertEquals(
        Arrays.asList("java", "sql", "go"),
        readService.findArticleTags(Arrays.asList("a-1")).stream()
            .map(r -> r.getTagName())
            .collect(java.util.stream.Collectors.toList()));
  }

  @Test
  public void should_handle_empty_tag_list() {
    ArticleTagsData result = tagCommandService.setTags("a-1", Collections.emptyList());
    assertEquals("a-1", result.getArticleId());
    assertTrue(result.getTagList().isEmpty());
    assertTrue(readService.all().isEmpty());
  }

  @Test
  public void should_keep_insertion_order_for_tags_and_article_ids() {
    tagCommandService.setTags(
        "a-2", Arrays.asList(new Tag("t-2", "spring"), new Tag("t-1", "java")));
    tagCommandService.setTags("a-1", Arrays.asList(new Tag("t-1", "java")));
    tagCommandService.setTags("a-3", Arrays.asList(new Tag("t-1", "java")));
    assertEquals(Arrays.asList("spring", "java"), readService.all());
    assertEquals(Arrays.asList("a-2", "a-1", "a-3"), readService.findArticleIdsByTagName("java"));
    assertEquals(Arrays.asList("a-2"), readService.findArticleIdsByTagName("spring"));
    assertEquals(Collections.emptyList(), readService.findArticleIdsByTagName("unknown"));
    assertEquals(Arrays.asList("spring", "java"), tagRepository.findTagNames("a-2"));
  }

  @Test
  public void should_return_distinct_article_ids_when_duplicate_pairs_exist() {
    tagRepository.insert(new Tag("t-1", "java"));
    tagRepository.insertRelation("a-1", "t-1");
    tagRepository.insertRelation("a-1", "t-1");
    List<String> ids = readService.findArticleIdsByTagName("java");
    assertEquals(Arrays.asList("a-1"), ids);
  }

  @Test
  public void should_find_by_name_return_first_row_when_names_duplicate() {
    tagRepository.insert(new Tag("t-1", "java"));
    tagRepository.insert(new Tag("t-2", "java"));
    assertEquals("t-1", tagRepository.findByName("java").get().getId());
    assertEquals(Arrays.asList("java", "java"), readService.all());
  }
}
