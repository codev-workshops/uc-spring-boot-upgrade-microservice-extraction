package io.spring.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.InternalFavoriteData;
import io.spring.application.data.InternalProfileData;
import io.spring.application.data.UserData;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InternalQueryServiceTest {
  private UserReadService userReadService;
  private UserRelationshipQueryService relationships;
  private ArticleFavoritesReadService favorites;
  private InternalQueryService service;
  private User me;

  @BeforeEach
  void setUp() {
    userReadService = mock(UserReadService.class);
    relationships = mock(UserRelationshipQueryService.class);
    favorites = mock(ArticleFavoritesReadService.class);
    service = new InternalQueryService(userReadService, relationships, favorites);
    me = new User("me@x.com", "me", "pw", "", "");
  }

  @Test
  void should_return_followed_user_ids() {
    when(relationships.followedUsers(me.getId())).thenReturn(List.of("u2"));
    assertEquals(List.of("u2"), service.followedUserIds(me));
  }

  @Test
  void should_mark_followed_profiles_for_current_user() {
    when(userReadService.findByIds(List.of("u2", "u3")))
        .thenReturn(
            List.of(
                new UserData("u2", "e2", "two", "b", "i"),
                new UserData("u3", "e3", "three", "b", "i")));
    when(relationships.followingAuthors(me.getId(), List.of("u2", "u3"))).thenReturn(Set.of("u3"));
    List<InternalProfileData> profiles = service.profiles(List.of("u2", "u3"), null, me);
    assertEquals(
        List.of(
            new InternalProfileData("u2", "two", "b", "i", false),
            new InternalProfileData("u3", "three", "b", "i", true)),
        profiles);
  }

  @Test
  void should_lookup_profile_by_username_anonymously() {
    when(userReadService.findByUsername("two")).thenReturn(new UserData("u2", "e", "two", "", ""));
    assertEquals(1, service.profiles(null, "two", null).size());
    verify(relationships, never()).followingAuthors(any(), any());
  }

  @Test
  void should_return_no_profiles_without_ids_or_username() {
    assertTrue(service.profiles(List.of(), null, me).isEmpty());
    verifyNoInteractions(userReadService, relationships);
  }

  @Test
  void should_default_favorites_for_unfavorited_articles() {
    when(favorites.favoriteCountsByArticleIds(List.of("a1", "a2")))
        .thenReturn(List.of(new ArticleFavoriteCount("a1", 4)));
    List<InternalFavoriteData> result = service.favorites(List.of("a1", "a2"), null);
    assertEquals(
        List.of(new InternalFavoriteData("a1", 4, false), new InternalFavoriteData("a2", 0, false)),
        result);
    verify(favorites, never()).favoritedArticleIdsOfUser(any(), any());
  }

  @Test
  void should_return_articles_favorited_by_user() {
    when(favorites.articleIdsFavoritedBy("two")).thenReturn(List.of("a1"));
    assertEquals(List.of("a1"), service.articleIdsFavoritedBy("two"));
  }
}
