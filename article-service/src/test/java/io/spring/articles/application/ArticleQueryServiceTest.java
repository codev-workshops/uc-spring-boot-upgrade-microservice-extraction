package io.spring.articles.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.articles.application.data.ArticleData;
import io.spring.articles.application.data.ArticleDataList;
import io.spring.articles.core.user.CurrentUser;
import io.spring.articles.infrastructure.client.UserServiceClient;
import io.spring.articles.infrastructure.client.dto.FavoriteDto;
import io.spring.articles.infrastructure.client.dto.ProfileDto;
import io.spring.articles.infrastructure.mybatis.readservice.ArticleReadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArticleQueryServiceTest {
  private ArticleReadService readService;
  private UserServiceClient client;
  private ArticleQueryService service;
  private final CurrentUser me = new CurrentUser("user-1", "john", "tok");

  @BeforeEach
  void setUp() {
    readService = mock(ArticleReadService.class);
    client = mock(UserServiceClient.class);
    service = new ArticleQueryService(readService, client);
  }

  private ArticleData article(String id, String authorId) {
    ArticleData data = new ArticleData();
    data.setId(id);
    data.setAuthorId(authorId);
    data.setTagList(new ArrayList<>());
    return data;
  }

  @Test
  void should_enrich_article_with_author_and_favorites() {
    when(readService.findBySlug("s")).thenReturn(article("a1", "user-2"));
    when(client.findProfilesByIds(List.of("user-2"), "tok"))
        .thenReturn(List.of(new ProfileDto("user-2", "jane", "bio", "img", true)));
    when(client.findFavorites(List.of("a1"), "tok"))
        .thenReturn(List.of(new FavoriteDto("a1", 5, true)));

    ArticleData data = service.findBySlug("s", me).get();
    assertEquals("jane", data.getProfileData().getUsername());
    assertTrue(data.getProfileData().isFollowing());
    assertEquals(5, data.getFavoritesCount());
    assertTrue(data.isFavorited());
  }

  @Test
  void should_keep_favorite_defaults_when_none_returned() {
    when(readService.findById("a1")).thenReturn(article("a1", "user-2"));
    ArticleData data = service.findById("a1", null).get();
    assertEquals(0, data.getFavoritesCount());
    assertFalse(data.isFavorited());
  }

  @Test
  void should_return_empty_for_missing_article() {
    assertTrue(service.findBySlug("missing", me).isEmpty());
    verifyNoInteractions(client);
  }

  @Test
  void should_short_circuit_unknown_author_and_empty_favorites() {
    when(client.findProfileByUsername(eq("nobody"), any())).thenReturn(Optional.empty());
    when(client.findArticleIdsFavoritedBy("lonely")).thenReturn(List.of());
    assertEquals(0, service.findRecentArticles(null, "nobody", null, new Page(), me).getCount());
    assertEquals(0, service.findRecentArticles(null, null, "lonely", new Page(), me).getCount());
    verifyNoInteractions(readService);
  }

  @Test
  void should_build_feed_from_followed_authors() {
    Page page = new Page();
    when(client.findFollowedUserIds("tok")).thenReturn(List.of("user-2"));
    when(readService.queryArticles(null, List.of("user-2"), null, page)).thenReturn(List.of("a1"));
    when(readService.countArticle(null, List.of("user-2"), null)).thenReturn(1);
    when(readService.findArticles(List.of("a1"))).thenReturn(List.of(article("a1", "user-2")));

    ArticleDataList feed = service.findUserFeed(me, page);
    assertEquals(1, feed.getCount());
    assertEquals("a1", feed.getArticleDatas().get(0).getId());
  }

  @Test
  void should_return_empty_feed_without_follows() {
    when(client.findFollowedUserIds("tok")).thenReturn(List.of());
    assertEquals(0, service.findUserFeed(me, new Page()).getCount());
    verifyNoInteractions(readService);
  }
}
