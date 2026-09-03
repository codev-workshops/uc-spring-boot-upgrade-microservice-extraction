package io.spring.article.infrastructure.article;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.article.application.ArticleCommandService;
import io.spring.article.core.article.Article;
import io.spring.article.core.article.ArticleRepository;
import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import java.util.Arrays;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Mirror of the monolith's ArticleRepositoryTransactionTest: when a tag insert fails, the article
 * row inserted in the same transaction must be rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
public class ArticleRepositoryTransactionTest {
  @Autowired private ArticleCommandService commandService;
  @Autowired private ArticleRepository articleRepository;
  @Autowired private TagRepository tagRepository;

  @Test
  public void should_roll_back_article_insert_when_tag_insert_fails() {
    tagRepository.insert(new Tag("tx-tag", "tx-java"));
    Article article =
        new Article(
            "tx-a-1",
            null,
            "Tx Title",
            "d",
            "b",
            "u-1",
            new DateTime(),
            new DateTime(),
            Arrays.asList(new Tag("tx-tag", "tx-other-name")));
    assertThrows(DataAccessException.class, () -> commandService.create(article));
    assertFalse(articleRepository.findById("tx-a-1").isPresent());
    assertFalse(articleRepository.findBySlug("tx-title").isPresent());
    assertTrue(tagRepository.findByName("tx-java").isPresent());
    assertFalse(tagRepository.relationExists("tx-a-1", "tx-tag"));
  }
}
