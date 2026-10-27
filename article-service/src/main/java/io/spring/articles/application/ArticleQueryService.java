package io.spring.articles.application;

import static java.util.stream.Collectors.toMap;

import io.spring.articles.application.data.ArticleData;
import io.spring.articles.application.data.ArticleDataList;
import io.spring.articles.application.data.ProfileData;
import io.spring.articles.core.user.CurrentUser;
import io.spring.articles.infrastructure.client.UserServiceClient;
import io.spring.articles.infrastructure.client.dto.FavoriteDto;
import io.spring.articles.infrastructure.client.dto.ProfileDto;
import io.spring.articles.infrastructure.mybatis.readservice.ArticleReadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class ArticleQueryService {
  private ArticleReadService articleReadService;
  private UserServiceClient userServiceClient;

  public Optional<ArticleData> findById(String id, CurrentUser user) {
    return enrich(articleReadService.findById(id), user);
  }

  public Optional<ArticleData> findBySlug(String slug, CurrentUser user) {
    return enrich(articleReadService.findBySlug(slug), user);
  }

  public ArticleDataList findRecentArticles(
      String tag, String author, String favoritedBy, Page page, CurrentUser currentUser) {
    List<String> authorIds = null;
    if (author != null) {
      Optional<ProfileDto> profile =
          userServiceClient.findProfileByUsername(author, tokenOf(currentUser));
      if (profile.isEmpty()) {
        return emptyList();
      }
      authorIds = List.of(profile.get().id());
    }
    List<String> favoritedArticleIds = null;
    if (favoritedBy != null) {
      favoritedArticleIds = userServiceClient.findArticleIdsFavoritedBy(favoritedBy);
      if (favoritedArticleIds.isEmpty()) {
        return emptyList();
      }
    }
    return query(tag, authorIds, favoritedArticleIds, page, currentUser);
  }

  public ArticleDataList findUserFeed(CurrentUser user, Page page) {
    List<String> followedUsers = userServiceClient.findFollowedUserIds(user.getToken());
    if (followedUsers.isEmpty()) {
      return emptyList();
    }
    return query(null, followedUsers, null, page, user);
  }

  private ArticleDataList query(
      String tag, List<String> authorIds, List<String> articleIds, Page page, CurrentUser user) {
    List<String> ids = articleReadService.queryArticles(tag, authorIds, articleIds, page);
    int count = articleReadService.countArticle(tag, authorIds, articleIds);
    if (ids.isEmpty()) {
      return new ArticleDataList(new ArrayList<>(), count);
    }
    List<ArticleData> articles = articleReadService.findArticles(ids);
    fillExtraInfo(articles, user);
    return new ArticleDataList(articles, count);
  }

  private Optional<ArticleData> enrich(ArticleData articleData, CurrentUser user) {
    if (articleData == null) {
      return Optional.empty();
    }
    fillExtraInfo(List.of(articleData), user);
    return Optional.of(articleData);
  }

  private void fillExtraInfo(List<ArticleData> articles, CurrentUser user) {
    String token = tokenOf(user);
    Map<String, ProfileDto> profiles =
        userServiceClient
            .findProfilesByIds(
                articles.stream().map(ArticleData::getAuthorId).distinct().toList(), token)
            .stream()
            .collect(toMap(ProfileDto::id, Function.identity(), (a, b) -> a));
    Map<String, FavoriteDto> favorites =
        userServiceClient
            .findFavorites(articles.stream().map(ArticleData::getId).toList(), token)
            .stream()
            .collect(toMap(FavoriteDto::articleId, Function.identity(), (a, b) -> a));
    for (ArticleData article : articles) {
      ProfileDto profile = profiles.get(article.getAuthorId());
      if (profile != null) {
        article.setProfileData(
            new ProfileData(
                profile.id(),
                profile.username(),
                profile.bio(),
                profile.image(),
                profile.following()));
      }
      FavoriteDto favorite = favorites.get(article.getId());
      if (favorite != null) {
        article.setFavoritesCount(favorite.favoritesCount());
        article.setFavorited(favorite.favorited());
      }
    }
  }

  private static String tokenOf(CurrentUser user) {
    return user == null ? null : user.getToken();
  }

  private static ArticleDataList emptyList() {
    return new ArticleDataList(new ArrayList<>(), 0);
  }
}
