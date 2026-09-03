package io.spring.application.tag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.ArticleQueryService;
import io.spring.application.Page;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ArticleTagList;
import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleQueryService} with the Tag port owning tag reads: {@code tagList} comes from one
 * batched {@code tagsByArticleIds} call, {@code tag=} is resolved to an id list (no SQL for an
 * unknown tag) and composes with the Favorite {@code favorited=} id list.
 */
public class TagRoutedArticleQueryServiceTest {
  private final ArticleReadService articleReadService = mock(ArticleReadService.class);
  private final UserRelationshipQueryService relationships =
      mock(UserRelationshipQueryService.class);
  private final FavoriteQueryPort favorites = mock(FavoriteQueryPort.class);
  private final UserReadService userReadService = mock(UserReadService.class);
  private final TagQueryPort tags = mock(TagQueryPort.class);
  private final ArticleQueryService service =
      new ArticleQueryService(articleReadService, relationships, favorites, userReadService, tags);
  private final Page page = new Page(0, 20);

  @BeforeEach
  public void setUp() {
    when(tags.ownsTagReads()).thenReturn(true);
    when(favorites.articlesFavoriteCount(anyList()))
        .thenAnswer(
            invocation -> {
              List<ArticleFavoriteCount> counts = new ArrayList<>();
              for (String id : invocation.<List<String>>getArgument(0)) {
                counts.add(new ArticleFavoriteCount(id, 0));
              }
              return counts;
            });
  }

  @Test
  public void tag_list_is_filled_from_one_batched_port_call() {
    when(articleReadService.queryArticles(null, null, null, page))
        .thenReturn(Arrays.asList("a", "b"));
    when(articleReadService.countArticle(null, null, null)).thenReturn(2);
    when(articleReadService.findArticles(Arrays.asList("a", "b")))
        .thenReturn(Arrays.asList(article("a", "stale"), article("b")));
    when(tags.tagsByArticleIds(Arrays.asList("a", "b")))
        .thenReturn(
            Arrays.asList(
                new ArticleTagList("a", Arrays.asList("java", "spring")),
                new ArticleTagList("b", Collections.emptyList())));

    ArticleDataList result = service.findRecentArticles(null, null, null, page, null);

    Assertions.assertEquals(2, result.getCount());
    Assertions.assertEquals(
        Arrays.asList("java", "spring"), result.getArticleDatas().get(0).getTagList());
    Assertions.assertTrue(result.getArticleDatas().get(1).getTagList().isEmpty());
    verify(tags).tagsByArticleIds(anyList());
  }

  @Test
  public void single_article_reads_fill_tag_list_from_the_port() {
    when(articleReadService.findBySlug("slug")).thenReturn(article("a", "stale"));
    when(articleReadService.findById("a")).thenReturn(article("a", "stale"));
    when(tags.tagsByArticleIds(Collections.singletonList("a")))
        .thenReturn(
            Collections.singletonList(new ArticleTagList("a", Collections.singletonList("java"))));

    Assertions.assertEquals(
        Collections.singletonList("java"), service.findBySlug("slug", null).get().getTagList());
    Assertions.assertEquals(
        Collections.singletonList("java"), service.findById("a", null).get().getTagList());
  }

  @Test
  public void tag_filter_is_resolved_to_an_id_list_and_removed_from_the_sql() {
    when(tags.articleIdsByTag("java")).thenReturn(Arrays.asList("a", "b"));
    when(articleReadService.queryArticlesByIds(
            isNull(), isNull(), isNull(), eq(Arrays.asList("a", "b")), eq(page)))
        .thenReturn(Collections.singletonList("a"));
    when(articleReadService.countArticleByIds(
            isNull(), isNull(), isNull(), eq(Arrays.asList("a", "b"))))
        .thenReturn(1);
    when(articleReadService.findArticles(Collections.singletonList("a")))
        .thenReturn(Collections.singletonList(article("a")));
    when(tags.tagsByArticleIds(anyList()))
        .thenReturn(
            Collections.singletonList(new ArticleTagList("a", Collections.singletonList("java"))));

    ArticleDataList result = service.findRecentArticles("java", null, null, page, null);

    Assertions.assertEquals(1, result.getCount());
    Assertions.assertEquals("a", result.getArticleDatas().get(0).getId());
    verify(articleReadService, never()).queryArticles(any(), any(), any(), any());
  }

  @Test
  public void unknown_tag_yields_empty_list_and_zero_count_without_sql() {
    when(tags.articleIdsByTag("nope")).thenReturn(Collections.emptyList());

    ArticleDataList result = service.findRecentArticles("nope", null, null, page, null);

    Assertions.assertEquals(0, result.getCount());
    Assertions.assertTrue(result.getArticleDatas().isEmpty());
    verify(articleReadService, never()).queryArticles(any(), any(), any(), any());
    verify(articleReadService, never()).queryArticlesByIds(any(), any(), any(), any(), any());
    verify(articleReadService, never()).countArticle(any(), any(), any());
    verify(articleReadService, never()).countArticleByIds(any(), any(), any(), any());
  }

  @Test
  public void tag_and_favorited_id_lists_are_intersected() {
    when(favorites.ownsFavoritedByFilter()).thenReturn(true);
    when(userReadService.findByUsername("reader"))
        .thenReturn(new UserData("u1", "reader@test.com", "reader", "", ""));
    when(favorites.articleIdsFavoritedBy("u1")).thenReturn(Arrays.asList("b", "c"));
    when(tags.articleIdsByTag("java")).thenReturn(Arrays.asList("a", "b"));
    when(articleReadService.queryArticlesByIds(
            isNull(), isNull(), isNull(), eq(Collections.singletonList("b")), eq(page)))
        .thenReturn(Collections.singletonList("b"));
    when(articleReadService.countArticleByIds(
            isNull(), isNull(), isNull(), eq(Collections.singletonList("b"))))
        .thenReturn(1);
    when(articleReadService.findArticles(Collections.singletonList("b")))
        .thenReturn(Collections.singletonList(article("b")));
    when(tags.tagsByArticleIds(anyList()))
        .thenReturn(
            Collections.singletonList(new ArticleTagList("b", Collections.singletonList("java"))));

    ArticleDataList result = service.findRecentArticles("java", null, "reader", page, null);

    Assertions.assertEquals(1, result.getCount());
    Assertions.assertEquals("b", result.getArticleDatas().get(0).getId());
  }

  @Test
  public void tag_routed_alone_keeps_the_sql_favorited_filter() {
    when(favorites.ownsFavoritedByFilter()).thenReturn(false);
    when(tags.articleIdsByTag("java")).thenReturn(Arrays.asList("a", "b"));
    when(articleReadService.queryArticlesByIds(
            isNull(), isNull(), eq("reader"), eq(Arrays.asList("a", "b")), eq(page)))
        .thenReturn(Collections.singletonList("b"));
    when(articleReadService.countArticleByIds(
            isNull(), isNull(), eq("reader"), eq(Arrays.asList("a", "b"))))
        .thenReturn(1);
    when(articleReadService.findArticles(Collections.singletonList("b")))
        .thenReturn(Collections.singletonList(article("b")));
    when(tags.tagsByArticleIds(anyList()))
        .thenReturn(
            Collections.singletonList(new ArticleTagList("b", Collections.singletonList("java"))));

    ArticleDataList result = service.findRecentArticles("java", null, "reader", page, null);

    Assertions.assertEquals(1, result.getCount());
    Assertions.assertEquals("b", result.getArticleDatas().get(0).getId());
  }

  @Test
  public void favorited_only_routing_keeps_the_sql_tag_filter() {
    when(tags.ownsTagReads()).thenReturn(false);
    when(favorites.ownsFavoritedByFilter()).thenReturn(true);
    when(userReadService.findByUsername("reader"))
        .thenReturn(new UserData("u1", "reader@test.com", "reader", "", ""));
    when(favorites.articleIdsFavoritedBy("u1")).thenReturn(Arrays.asList("b", "c"));
    when(articleReadService.queryArticlesByIds(
            eq("java"), isNull(), isNull(), eq(Arrays.asList("b", "c")), eq(page)))
        .thenReturn(Collections.singletonList("b"));
    when(articleReadService.countArticleByIds(
            eq("java"), isNull(), isNull(), eq(Arrays.asList("b", "c"))))
        .thenReturn(1);
    when(articleReadService.findArticles(Collections.singletonList("b")))
        .thenReturn(Collections.singletonList(article("b", "java")));

    ArticleDataList result = service.findRecentArticles("java", null, "reader", page, null);

    Assertions.assertEquals(1, result.getCount());
    Assertions.assertEquals(
        Collections.singletonList("java"), result.getArticleDatas().get(0).getTagList());
    verify(tags, never()).articleIdsByTag(any());
    verify(tags, never()).tagsByArticleIds(any());
  }

  @Test
  public void flag_off_leaves_the_sql_result_untouched() {
    when(tags.ownsTagReads()).thenReturn(false);
    when(articleReadService.queryArticles("java", null, null, page))
        .thenReturn(Collections.singletonList("a"));
    when(articleReadService.countArticle("java", null, null)).thenReturn(1);
    when(articleReadService.findArticles(Collections.singletonList("a")))
        .thenReturn(Collections.singletonList(article("a", "java")));

    ArticleDataList result = service.findRecentArticles("java", null, null, page, null);

    Assertions.assertEquals(
        Collections.singletonList("java"), result.getArticleDatas().get(0).getTagList());
    verify(tags, never()).articleIdsByTag(any());
    verify(tags, never()).tagsByArticleIds(any());
  }

  private static ArticleData article(String id, String... tagList) {
    return new ArticleData(
        id,
        id,
        "title " + id,
        "desc",
        "body",
        false,
        0,
        new DateTime(),
        new DateTime(),
        new ArrayList<>(Arrays.asList(tagList)),
        new ProfileData("u", "author", "", "", false));
  }
}
