package io.spring.infrastructure.tag;

import io.spring.core.article.Article;
import io.spring.core.article.ArticleRepository;
import io.spring.core.article.Tag;
import io.spring.core.article.TagRepository;
import io.spring.infrastructure.DbTestBase;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import io.spring.infrastructure.repository.MyBatisTagRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

@Import({MyBatisTagRepository.class, MyBatisArticleRepository.class})
public class MyBatisTagRepositoryTest extends DbTestBase {
  @Autowired private TagRepository tagRepository;

  @Autowired private ArticleRepository articleRepository;

  @Test
  public void should_create_and_fetch_tag() {
    Tag tag = new Tag("java");
    tagRepository.save(tag);

    Assertions.assertEquals("java", tagRepository.findById(tag.getId()).get().getName());
    Assertions.assertEquals(tag.getId(), tagRepository.findByName("java").get().getId());
  }

  @Test
  public void should_list_tags_by_name() {
    tagRepository.save(new Tag("spring"));
    tagRepository.save(new Tag("java"));

    List<String> names =
        tagRepository.findAll().stream().map(Tag::getName).collect(Collectors.toList());
    Assertions.assertEquals(Arrays.asList("java", "spring"), names);
  }

  @Test
  public void should_rename_tag() {
    Tag tag = new Tag("jav");
    tagRepository.save(tag);

    tag.setName("java");
    tagRepository.save(tag);

    Assertions.assertEquals("java", tagRepository.findById(tag.getId()).get().getName());
    Assertions.assertFalse(tagRepository.findByName("jav").isPresent());
  }

  @Test
  public void should_remove_tag_and_its_article_links() {
    Article article =
        new Article("title", "desc", "body", Arrays.asList("java", "spring"), "user-1");
    articleRepository.save(article);
    Tag java = tagRepository.findByName("java").get();

    tagRepository.remove(java);

    Assertions.assertFalse(tagRepository.findById(java.getId()).isPresent());
    Article fetched = articleRepository.findById(article.getId()).get();
    Assertions.assertEquals(Collections.singletonList(new Tag("spring")), fetched.getTags());
  }

  @Test
  public void should_reject_duplicate_tag_name() {
    tagRepository.save(new Tag("java"));

    Assertions.assertThrows(DataAccessException.class, () -> tagRepository.save(new Tag("java")));
  }

  @Test
  public void should_reject_blank_tag_name() {
    Assertions.assertThrows(DataAccessException.class, () -> tagRepository.save(new Tag("  ")));
  }

  @Test
  public void should_share_tag_between_articles() {
    articleRepository.save(new Article("one", "desc", "body", Arrays.asList("java"), "user-1"));
    articleRepository.save(new Article("two", "desc", "body", Arrays.asList("java"), "user-1"));

    Assertions.assertEquals(1, tagRepository.findAll().size());
  }

  @Test
  public void should_remove_article_tag_links_when_article_removed() {
    Article article = new Article("title", "desc", "body", Arrays.asList("java"), "user-1");
    articleRepository.save(article);

    articleRepository.remove(article);

    Tag java = tagRepository.findByName("java").get();
    tagRepository.remove(java);
    Assertions.assertTrue(tagRepository.findAll().isEmpty());
  }
}
