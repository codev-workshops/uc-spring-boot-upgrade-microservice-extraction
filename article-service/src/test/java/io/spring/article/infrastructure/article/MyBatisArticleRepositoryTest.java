package io.spring.article.infrastructure.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.spring.article.api.exception.DuplicatedArticleException;
import io.spring.article.api.exception.ResourceNotFoundException;
import io.spring.article.application.ArticleCommandService;
import io.spring.article.application.ArticleQueryService;
import io.spring.article.application.CursorPageParameter;
import io.spring.article.application.CursorPageParameter.Direction;
import io.spring.article.application.Page;
import io.spring.article.application.data.ArticleData;
import io.spring.article.application.data.ArticleIdsData;
import io.spring.article.application.data.ArticleListData;
import io.spring.article.core.article.Article;
import io.spring.article.core.article.ArticleRepository;
import io.spring.article.core.tag.Tag;
import io.spring.article.core.tag.TagRepository;
import io.spring.article.infrastructure.DbTestBase;
import io.spring.article.infrastructure.repository.MyBatisArticleRepository;
import io.spring.article.infrastructure.repository.MyBatisTagRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({
  MyBatisArticleRepository.class,
  MyBatisTagRepository.class,
  ArticleQueryService.class,
  ArticleCommandService.class
})
public class MyBatisArticleRepositoryTest extends DbTestBase {
  private static final DateTime T0 = new DateTime(2024, 1, 1, 10, 0, 0, 123, DateTimeZone.UTC);

  @Autowired private ArticleRepository articleRepository;
  @Autowired private TagRepository tagRepository;
  @Autowired private ArticleQueryService queryService;
  @Autowired private ArticleCommandService commandService;

  private Article article(String id, String userId, int minutes, String... tags) {
    return new Article(
        id,
        null,
        "Title " + id,
        "desc " + id,
        "body " + id,
        userId,
        T0.plusMinutes(minutes),
        T0.plusMinutes(minutes),
        Arrays.stream(tags).map(t -> new Tag("tag-" + t, t)).collect(Collectors.toList()));
  }

  @Test
  public void should_create_article_with_tags_and_keep_supplied_values() {
    ArticleCommandService.CreateResult result =
        commandService.create(article("a-1", "u-1", 0, "java", "sql"));
    assertTrue(result.isCreated());
    ArticleData data = result.getArticle();
    assertEquals("a-1", data.getId());
    assertEquals("title-a-1", data.getSlug());
    assertEquals("u-1", data.getUserId());
    assertEquals(T0.getMillis(), data.getCreatedAt().getMillis());
    assertEquals(T0.getMillis(), data.getUpdatedAt().getMillis());
    assertEquals(Arrays.asList("java", "sql"), data.getTagList());
    assertEquals("tag-java", tagRepository.findByName("java").get().getId());
    assertTrue(tagRepository.relationExists("a-1", "tag-java"));

    Article stored = articleRepository.findBySlug("title-a-1").get();
    assertEquals("a-1", stored.getId());
    assertEquals(2, stored.getTags().size());
  }

  @Test
  public void should_reuse_existing_tag_by_name_on_create() {
    tagRepository.insert(new Tag("existing", "java"));
    commandService.create(article("a-1", "u-1", 0, "java"));
    assertTrue(tagRepository.relationExists("a-1", "existing"));
    assertFalse(tagRepository.relationExists("a-1", "tag-java"));
  }

  @Test
  public void should_return_existing_row_unchanged_when_recreating_same_id() {
    commandService.create(article("a-1", "u-1", 0, "java"));
    Article again =
        new Article(
            "a-1", "other-slug", "Other", "d", "b", "u-2", T0.plusDays(1), T0.plusDays(1), null);
    ArticleCommandService.CreateResult result = commandService.create(again);
    assertFalse(result.isCreated());
    assertEquals("title-a-1", result.getArticle().getSlug());
    assertEquals("u-1", result.getArticle().getUserId());
  }

  @Test
  public void should_reject_slug_owned_by_another_id() {
    commandService.create(article("a-1", "u-1", 0));
    Article clash = article("a-2", "u-1", 1);
    Article sameSlug =
        new Article(
            clash.getId(),
            "title-a-1",
            clash.getTitle(),
            clash.getDescription(),
            clash.getBody(),
            clash.getUserId(),
            clash.getCreatedAt(),
            clash.getUpdatedAt(),
            null);
    assertThrows(DuplicatedArticleException.class, () -> commandService.create(sameSlug));
    assertFalse(articleRepository.findById("a-2").isPresent());
  }

  @Test
  public void should_update_only_non_blank_fields_and_not_touch_updated_at() {
    commandService.create(article("a-1", "u-1", 0));
    ArticleData updated = commandService.update("a-1", "New Title", "", null);
    assertEquals("New Title", updated.getTitle());
    assertEquals("new-title", updated.getSlug());
    assertEquals("desc a-1", updated.getDescription());
    assertEquals("body a-1", updated.getBody());
    assertEquals(T0.getMillis(), updated.getUpdatedAt().getMillis());

    ArticleData bodyOnly = commandService.update("a-1", "", "", "new body");
    assertEquals("New Title", bodyOnly.getTitle());
    assertEquals("new-title", bodyOnly.getSlug());
    assertEquals("new body", bodyOnly.getBody());
    assertEquals(T0.getMillis(), bodyOnly.getUpdatedAt().getMillis());

    ArticleData noop = commandService.update("a-1", null, null, null);
    assertEquals("new body", noop.getBody());
  }

  @Test
  public void should_reject_update_to_slug_owned_by_another_article_but_allow_own_slug() {
    commandService.create(article("a-1", "u-1", 0));
    commandService.create(article("a-2", "u-1", 1));
    assertThrows(
        DuplicatedArticleException.class, () -> commandService.update("a-2", "Title a-1", "", ""));
    assertEquals("Title a-1", commandService.update("a-1", "Title a-1", "", "").getTitle());
    assertThrows(
        ResourceNotFoundException.class, () -> commandService.update("missing", "x", "", ""));
  }

  @Test
  public void should_delete_only_the_article_row_and_be_idempotent() {
    commandService.create(article("a-1", "u-1", 0, "java"));
    commandService.delete("a-1");
    commandService.delete("a-1");
    assertFalse(articleRepository.findById("a-1").isPresent());
    assertFalse(queryService.findById("a-1").isPresent());
    assertTrue(tagRepository.relationExists("a-1", "tag-java"));
    assertTrue(tagRepository.findByName("java").isPresent());
  }

  @Test
  public void should_list_ids_distinct_desc_with_paging_and_count() {
    commandService.create(article("a-1", "u-1", 0, "java", "sql"));
    commandService.create(article("a-2", "u-2", 1, "java"));
    commandService.create(article("a-3", "u-1", 2));

    ArticleIdsData all = queryService.findArticleIds(null, null, null, new Page(0, 20));
    assertEquals(Arrays.asList("a-3", "a-2", "a-1"), all.getArticleIds());
    assertEquals(3, all.getCount());

    ArticleIdsData page = queryService.findArticleIds(null, null, null, new Page(1, 1));
    assertEquals(Arrays.asList("a-2"), page.getArticleIds());
    assertEquals(3, page.getCount());

    ArticleIdsData byTag = queryService.findArticleIds("java", null, null, new Page(0, 20));
    assertEquals(Arrays.asList("a-2", "a-1"), byTag.getArticleIds());
    assertEquals(2, byTag.getCount());

    ArticleIdsData byAuthor = queryService.findArticleIds(null, "u-1", null, new Page(0, 20));
    assertEquals(Arrays.asList("a-3", "a-1"), byAuthor.getArticleIds());
    assertEquals(2, byAuthor.getCount());

    ArticleIdsData byIds =
        queryService.findArticleIds(null, null, Arrays.asList("a-1", "a-2"), new Page(0, 20));
    assertEquals(Arrays.asList("a-2", "a-1"), byIds.getArticleIds());
    assertEquals(2, byIds.getCount());

    ArticleIdsData combined =
        queryService.findArticleIds("java", "u-1", Arrays.asList("a-1", "a-3"), new Page(0, 20));
    assertEquals(Arrays.asList("a-1"), combined.getArticleIds());
    assertEquals(1, combined.getCount());

    ArticleIdsData emptyAllowList =
        queryService.findArticleIds(null, null, Collections.emptyList(), new Page(0, 20));
    assertEquals(0, emptyAllowList.getCount());
    assertTrue(emptyAllowList.getArticleIds().isEmpty());

    ArticleIdsData unknownAuthor =
        queryService.findArticleIds(null, "nobody", null, new Page(0, 20));
    assertEquals(0, unknownAuthor.getCount());
  }

  @Test
  public void should_page_ids_with_cursor_limit_plus_one_and_direction() {
    commandService.create(article("a-1", "u-1", 0, "java"));
    commandService.create(article("a-2", "u-1", 1, "java"));
    commandService.create(article("a-3", "u-1", 2));
    commandService.create(article("a-4", "u-2", 3, "java"));

    List<String> first =
        queryService.findArticleIdsWithCursor(
            null, null, null, new CursorPageParameter(null, 2, Direction.NEXT));
    assertEquals(Arrays.asList("a-4", "a-3", "a-2"), first);

    List<String> next =
        queryService.findArticleIdsWithCursor(
            null, null, null, new CursorPageParameter(T0.plusMinutes(2), 2, Direction.NEXT));
    assertEquals(Arrays.asList("a-2", "a-1"), next);

    List<String> prev =
        queryService.findArticleIdsWithCursor(
            null, null, null, new CursorPageParameter(T0.plusMinutes(1), 2, Direction.PREV));
    assertEquals(Arrays.asList("a-3", "a-4"), prev);

    List<String> filtered =
        queryService.findArticleIdsWithCursor(
            "java",
            "u-1",
            Arrays.asList("a-1", "a-2", "a-4"),
            new CursorPageParameter(null, 10, Direction.NEXT));
    assertEquals(Arrays.asList("a-2", "a-1"), filtered);

    assertTrue(
        queryService
            .findArticleIdsWithCursor(
                null,
                null,
                Collections.emptyList(),
                new CursorPageParameter(null, 10, Direction.NEXT))
            .isEmpty());
  }

  @Test
  public void should_find_articles_by_ids_desc_and_feed_by_author_ids() {
    commandService.create(article("a-1", "u-1", 0, "java"));
    commandService.create(article("a-2", "u-2", 1));
    commandService.create(article("a-3", "u-3", 2));

    List<ArticleData> rows = queryService.findArticles(Arrays.asList("a-1", "a-3"));
    assertEquals(
        Arrays.asList("a-3", "a-1"),
        rows.stream().map(ArticleData::getId).collect(Collectors.toList()));
    assertEquals(Arrays.asList("java"), rows.get(1).getTagList());
    assertTrue(queryService.findArticles(Collections.emptyList()).isEmpty());

    ArticleListData feed = queryService.findUserFeed(Arrays.asList("u-1", "u-2"), new Page(0, 20));
    assertEquals(2, feed.getCount());
    assertEquals(2, feed.getArticles().size());
    assertEquals(
        1,
        queryService
            .findUserFeed(Arrays.asList("u-1", "u-2"), new Page(1, 1))
            .getArticles()
            .size());
    assertEquals(0, queryService.findUserFeed(Collections.emptyList(), new Page(0, 20)).getCount());

    List<ArticleData> cursorFeed =
        queryService.findUserFeedWithCursor(
            Arrays.asList("u-1", "u-2", "u-3"), new CursorPageParameter(null, 1, Direction.NEXT));
    assertEquals(
        Arrays.asList("a-3", "a-2"),
        cursorFeed.stream().map(ArticleData::getId).collect(Collectors.toList()));
    List<ArticleData> prevFeed =
        queryService.findUserFeedWithCursor(
            Arrays.asList("u-1", "u-2", "u-3"), new CursorPageParameter(T0, 5, Direction.PREV));
    assertEquals(
        Arrays.asList("a-2", "a-3"),
        prevFeed.stream().map(ArticleData::getId).collect(Collectors.toList()));
    assertTrue(
        queryService
            .findUserFeedWithCursor(
                Collections.emptyList(), new CursorPageParameter(null, 5, Direction.NEXT))
            .isEmpty());
  }
}
