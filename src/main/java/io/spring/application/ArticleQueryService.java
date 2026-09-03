package io.spring.application;

import static java.util.stream.Collectors.toList;

import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.UserData;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ArticleQueryService {
  private final ArticleReadService articleReadService;
  private final UserRelationshipQueryService userRelationshipQueryService;
  private final FavoriteQueryPort favoriteQueryPort;
  private final UserReadService userReadService;

  @Autowired
  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService) {
    this.articleReadService = articleReadService;
    this.userRelationshipQueryService = userRelationshipQueryService;
    this.favoriteQueryPort = favoriteQueryPort;
    this.userReadService = userReadService;
  }

  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort) {
    this(articleReadService, userRelationshipQueryService, favoriteQueryPort, null);
  }

  public Optional<ArticleData> findById(String id, User user) {
    ArticleData articleData = articleReadService.findById(id);
    if (articleData == null) {
      return Optional.empty();
    } else {
      if (user != null) {
        fillExtraInfo(id, user, articleData);
      }
      return Optional.of(articleData);
    }
  }

  public Optional<ArticleData> findBySlug(String slug, User user) {
    ArticleData articleData = articleReadService.findBySlug(slug);
    if (articleData == null) {
      return Optional.empty();
    } else {
      if (user != null) {
        fillExtraInfo(articleData.getId(), user, articleData);
      }
      return Optional.of(articleData);
    }
  }

  public CursorPager<ArticleData> findRecentArticlesWithCursor(
      String tag,
      String author,
      String favoritedBy,
      CursorPageParameter<DateTime> page,
      User currentUser) {
    List<String> articleIds;
    if (routeFavoritedByThroughPort(favoritedBy)) {
      List<String> favoritedIds = favoritedArticleIds(favoritedBy);
      articleIds =
          favoritedIds.isEmpty()
              ? new ArrayList<>()
              : articleReadService.findArticlesWithCursorByIds(tag, author, favoritedIds, page);
    } else {
      articleIds = articleReadService.findArticlesWithCursor(tag, author, favoritedBy, page);
    }
    if (articleIds.size() == 0) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    } else {
      boolean hasExtra = articleIds.size() > page.getLimit();
      if (hasExtra) {
        articleIds.remove(page.getLimit());
      }
      if (!page.isNext()) {
        Collections.reverse(articleIds);
      }

      List<ArticleData> articles = articleReadService.findArticles(articleIds);
      fillExtraInfo(articles, currentUser);

      return new CursorPager<>(articles, page.getDirection(), hasExtra);
    }
  }

  public CursorPager<ArticleData> findUserFeedWithCursor(
      User user, CursorPageParameter<DateTime> page) {
    List<String> followdUsers = userRelationshipQueryService.followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    } else {
      List<ArticleData> articles =
          articleReadService.findArticlesOfAuthorsWithCursor(followdUsers, page);
      boolean hasExtra = articles.size() > page.getLimit();
      if (hasExtra) {
        articles.remove(page.getLimit());
      }
      if (!page.isNext()) {
        Collections.reverse(articles);
      }
      fillExtraInfo(articles, user);
      return new CursorPager<>(articles, page.getDirection(), hasExtra);
    }
  }

  public ArticleDataList findRecentArticles(
      String tag, String author, String favoritedBy, Page page, User currentUser) {
    List<String> articleIds;
    int articleCount;
    if (routeFavoritedByThroughPort(favoritedBy)) {
      List<String> favoritedIds = favoritedArticleIds(favoritedBy);
      if (favoritedIds.isEmpty()) {
        articleIds = new ArrayList<>();
        articleCount = 0;
      } else {
        articleIds = articleReadService.queryArticlesByIds(tag, author, favoritedIds, page);
        articleCount = articleReadService.countArticleByIds(tag, author, favoritedIds);
      }
    } else {
      articleIds = articleReadService.queryArticles(tag, author, favoritedBy, page);
      articleCount = articleReadService.countArticle(tag, author, favoritedBy);
    }
    if (articleIds.size() == 0) {
      return new ArticleDataList(new ArrayList<>(), articleCount);
    } else {
      List<ArticleData> articles = articleReadService.findArticles(articleIds);
      fillExtraInfo(articles, currentUser);
      return new ArticleDataList(articles, articleCount);
    }
  }

  public ArticleDataList findUserFeed(User user, Page page) {
    List<String> followdUsers = userRelationshipQueryService.followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new ArticleDataList(new ArrayList<>(), 0);
    } else {
      List<ArticleData> articles = articleReadService.findArticlesOfAuthors(followdUsers, page);
      fillExtraInfo(articles, user);
      int count = articleReadService.countFeedSize(followdUsers);
      return new ArticleDataList(articles, count);
    }
  }

  private boolean routeFavoritedByThroughPort(String favoritedBy) {
    return favoritedBy != null
        && userReadService != null
        && favoriteQueryPort.ownsFavoritedByFilter();
  }

  private List<String> favoritedArticleIds(String username) {
    UserData user = userReadService.findByUsername(username);
    if (user == null) {
      return new ArrayList<>();
    }
    return favoriteQueryPort.articleIdsFavoritedBy(user.getId());
  }

  private void fillExtraInfo(List<ArticleData> articles, User currentUser) {
    setFavoriteCount(articles);
    if (currentUser != null) {
      setIsFavorite(articles, currentUser);
      setIsFollowingAuthor(articles, currentUser);
    }
  }

  private void setIsFollowingAuthor(List<ArticleData> articles, User currentUser) {
    Set<String> followingAuthors =
        userRelationshipQueryService.followingAuthors(
            currentUser.getId(),
            articles.stream()
                .map(articleData1 -> articleData1.getProfileData().getId())
                .collect(toList()));
    articles.forEach(
        articleData -> {
          if (followingAuthors.contains(articleData.getProfileData().getId())) {
            articleData.getProfileData().setFollowing(true);
          }
        });
  }

  private void setFavoriteCount(List<ArticleData> articles) {
    List<ArticleFavoriteCount> favoritesCounts =
        favoriteQueryPort.articlesFavoriteCount(
            articles.stream().map(ArticleData::getId).collect(toList()));
    Map<String, Integer> countMap = new HashMap<>();
    favoritesCounts.forEach(
        item -> {
          countMap.put(item.getId(), item.getCount());
        });
    articles.forEach(
        articleData -> articleData.setFavoritesCount(countMap.get(articleData.getId())));
  }

  private void setIsFavorite(List<ArticleData> articles, User currentUser) {
    Set<String> favoritedArticles =
        favoriteQueryPort.userFavorites(
            articles.stream().map(articleData -> articleData.getId()).collect(toList()),
            currentUser);

    articles.forEach(
        articleData -> {
          if (favoritedArticles.contains(articleData.getId())) {
            articleData.setFavorited(true);
          }
        });
  }

  private void fillExtraInfo(String id, User user, ArticleData articleData) {
    articleData.setFavorited(favoriteQueryPort.isUserFavorite(user.getId(), id));
    articleData.setFavoritesCount(favoriteQueryPort.articleFavoriteCount(id));
    articleData
        .getProfileData()
        .setFollowing(
            userRelationshipQueryService.isUserFollowing(
                user.getId(), articleData.getProfileData().getId()));
  }
}
