package io.spring.application;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.InternalFavoriteData;
import io.spring.application.data.InternalProfileData;
import io.spring.application.data.UserData;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleFavoritesReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/** Read models for the /internal/** API consumed by extracted services. */
@Service
@AllArgsConstructor
public class InternalQueryService {
  private UserReadService userReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private ArticleFavoritesReadService articleFavoritesReadService;

  public List<String> followedUserIds(User user) {
    return userRelationshipQueryService.followedUsers(user.getId());
  }

  public List<InternalProfileData> profiles(List<String> ids, String username, User currentUser) {
    List<UserData> users = findUsers(ids, username);
    Set<String> following =
        currentUser == null || users.isEmpty()
            ? Collections.emptySet()
            : userRelationshipQueryService.followingAuthors(
                currentUser.getId(),
                users.stream().map(UserData::getId).collect(Collectors.toList()));
    return users.stream()
        .map(
            u ->
                new InternalProfileData(
                    u.getId(),
                    u.getUsername(),
                    u.getBio(),
                    u.getImage(),
                    following.contains(u.getId())))
        .collect(Collectors.toList());
  }

  public List<InternalFavoriteData> favorites(List<String> articleIds, User currentUser) {
    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    Map<String, Integer> counts =
        articleFavoritesReadService.favoriteCountsByArticleIds(articleIds).stream()
            .collect(Collectors.toMap(ArticleFavoriteCount::getId, ArticleFavoriteCount::getCount));
    Set<String> favorited =
        currentUser == null
            ? Collections.emptySet()
            : articleFavoritesReadService.favoritedArticleIdsOfUser(
                currentUser.getId(), articleIds);
    return articleIds.stream()
        .distinct()
        .map(id -> new InternalFavoriteData(id, counts.getOrDefault(id, 0), favorited.contains(id)))
        .collect(Collectors.toList());
  }

  public List<String> articleIdsFavoritedBy(String username) {
    return articleFavoritesReadService.articleIdsFavoritedBy(username);
  }

  private List<UserData> findUsers(List<String> ids, String username) {
    if (username != null) {
      UserData found = userReadService.findByUsername(username);
      return found == null ? Collections.emptyList() : List.of(found);
    }
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    return userReadService.findByIds(ids);
  }
}
