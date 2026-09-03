package io.spring.application;

import static java.util.stream.Collectors.toList;

import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ArticleTagList;
import io.spring.application.data.UserData;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.application.tag.TagQueryPort;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.ArticleReadService;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
  private final TagQueryPort tagQueryPort;

  @Autowired
  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService,
      TagQueryPort tagQueryPort) {
    this.articleReadService = articleReadService;
    this.userRelationshipQueryService = userRelationshipQueryService;
    this.favoriteQueryPort = favoriteQueryPort;
    this.userReadService = userReadService;
    this.tagQueryPort = tagQueryPort;
  }

  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService) {
    this(
        articleReadService, userRelationshipQueryService, favoriteQueryPort, userReadService, null);
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
      setTagList(Collections.singletonList(articleData));
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
      setTagList(Collections.singletonList(articleData));
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
    IdFilter filter = resolveIdFilter(tag, favoritedBy);
    if (filter.routed()) {
      articleIds =
          filter.ids.isEmpty()
              ? new ArrayList<>()
              : articleReadService.findArticlesWithCursorByIds(
                  filter.sqlTag, author, filter.sqlFavoritedBy, filter.ids, page);
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
    IdFilter filter = resolveIdFilter(tag, favoritedBy);
    if (filter.routed()) {
      if (filter.ids.isEmpty()) {
        articleIds = new ArrayList<>();
        articleCount = 0;
      } else {
        articleIds =
            articleReadService.queryArticlesByIds(
                filter.sqlTag, author, filter.sqlFavoritedBy, filter.ids, page);
        articleCount =
            articleReadService.countArticleByIds(
                filter.sqlTag, author, filter.sqlFavoritedBy, filter.ids);
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

  /**
   * The {@code tag=} and {@code favorited=} filters each turn into an article-id list when their
   * domain has been extracted. Both lists are intersected here so the SQL only receives one {@code
   * A.id in (...)} clause; a filter whose domain is still in the monolith keeps its SQL join.
   */
  private static final class IdFilter {
    final String sqlTag;
    final String sqlFavoritedBy;
    final List<String> ids;

    IdFilter(String sqlTag, String sqlFavoritedBy, List<String> ids) {
      this.sqlTag = sqlTag;
      this.sqlFavoritedBy = sqlFavoritedBy;
      this.ids = ids;
    }

    boolean routed() {
      return ids != null;
    }
  }

  private IdFilter resolveIdFilter(String tag, String favoritedBy) {
    List<String> ids = null;
    String sqlTag = tag;
    String sqlFavoritedBy = favoritedBy;
    if (routeTagThroughPort(tag)) {
      ids = new ArrayList<>(tagQueryPort.articleIdsByTag(tag));
      sqlTag = null;
    }
    if (routeFavoritedByThroughPort(favoritedBy)) {
      List<String> favoritedIds = favoritedArticleIds(favoritedBy);
      sqlFavoritedBy = null;
      if (ids == null) {
        ids = favoritedIds;
      } else {
        ids.retainAll(new HashSet<>(favoritedIds));
      }
    }
    return new IdFilter(sqlTag, sqlFavoritedBy, ids);
  }

  private boolean routeTagThroughPort(String tag) {
    return tag != null && tagQueryPort != null && tagQueryPort.ownsTagReads();
  }

  private void setTagList(List<ArticleData> articles) {
    if (articles.isEmpty() || tagQueryPort == null || !tagQueryPort.ownsTagReads()) {
      return;
    }
    Map<String, List<String>> tagsByArticle = new HashMap<>();
    for (ArticleTagList entry :
        tagQueryPort.tagsByArticleIds(
            articles.stream().map(ArticleData::getId).collect(toList()))) {
      tagsByArticle.put(entry.getArticleId(), entry.getTagList());
    }
    articles.forEach(
        articleData ->
            articleData.setTagList(
                tagsByArticle.getOrDefault(articleData.getId(), new ArrayList<>())));
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
    setTagList(articles);
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
