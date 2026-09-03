package io.spring.application;

import static java.util.stream.Collectors.toList;

import io.spring.application.article.ArticleIdPage;
import io.spring.application.article.ArticleQueryPort;
import io.spring.application.article.ArticleRowPage;
import io.spring.application.data.ArticleData;
import io.spring.application.data.ArticleDataList;
import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.data.ArticleRow;
import io.spring.application.data.ArticleTagList;
import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.application.favorite.FavoriteQueryPort;
import io.spring.application.tag.TagQueryPort;
import io.spring.application.user.FollowPort;
import io.spring.application.user.UserQueryPort;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Read model of the public article API. While Article reads stay in the monolith every method runs
 * the SQL joins of {@code ArticleReadService.xml} (with the Phase 1/3 Favorite/Tag id-list
 * routing). When {@code extraction.article.read} is {@code extracted} or {@code shadow} the service
 * becomes a composer instead: rows and {@code tagList} come from the {@link ArticleQueryPort},
 * usernames in {@code author=}/{@code favorited=} are resolved locally before the port is called,
 * and author profile, {@code following} and favorites are added from the monolith/Favorite port.
 */
@Service
public class ArticleQueryService {
  private final ArticleReadService articleReadService;
  private final UserRelationshipQueryService userRelationshipQueryService;
  private final FavoriteQueryPort favoriteQueryPort;
  private final UserReadService userReadService;
  private final TagQueryPort tagQueryPort;
  private final ArticleQueryPort articleQueryPort;
  private final UserQueryPort userQueryPort;
  private final FollowPort followPort;

  @Autowired
  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService,
      TagQueryPort tagQueryPort,
      ObjectProvider<ArticleQueryPort> articleQueryPort,
      ObjectProvider<UserQueryPort> userQueryPort,
      ObjectProvider<FollowPort> followPort) {
    this(
        articleReadService,
        userRelationshipQueryService,
        favoriteQueryPort,
        userReadService,
        tagQueryPort,
        articleQueryPort.getIfAvailable(),
        userQueryPort.getIfAvailable(),
        followPort.getIfAvailable());
  }

  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService,
      TagQueryPort tagQueryPort,
      ArticleQueryPort articleQueryPort) {
    this(
        articleReadService,
        userRelationshipQueryService,
        favoriteQueryPort,
        userReadService,
        tagQueryPort,
        articleQueryPort,
        null,
        null);
  }

  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService,
      TagQueryPort tagQueryPort,
      ArticleQueryPort articleQueryPort,
      UserQueryPort userQueryPort,
      FollowPort followPort) {
    this.articleReadService = articleReadService;
    this.userRelationshipQueryService = userRelationshipQueryService;
    this.favoriteQueryPort = favoriteQueryPort;
    this.userReadService = userReadService;
    this.tagQueryPort = tagQueryPort;
    this.articleQueryPort = articleQueryPort;
    this.userQueryPort = userQueryPort;
    this.followPort = followPort;
  }

  public ArticleQueryService(
      ArticleReadService articleReadService,
      UserRelationshipQueryService userRelationshipQueryService,
      FavoriteQueryPort favoriteQueryPort,
      UserReadService userReadService,
      TagQueryPort tagQueryPort) {
    this(
        articleReadService,
        userRelationshipQueryService,
        favoriteQueryPort,
        userReadService,
        tagQueryPort,
        (ArticleQueryPort) null);
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
    if (routeArticleThroughPort()) {
      return composeOne(articleQueryPort.findById(id), user);
    }
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
    if (routeArticleThroughPort()) {
      return composeOne(articleQueryPort.findBySlug(slug), user);
    }
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
    if (routeArticleThroughPort()) {
      return findRecentArticlesWithCursorViaPort(tag, author, favoritedBy, page, currentUser);
    }
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
    List<String> followdUsers = followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    } else {
      List<ArticleData> articles =
          routeArticleThroughPort()
              ? compose(articleQueryPort.findArticlesOfAuthorsWithCursor(followdUsers, page))
              : articleReadService.findArticlesOfAuthorsWithCursor(followdUsers, page);
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
    if (routeArticleThroughPort()) {
      return findRecentArticlesViaPort(tag, author, favoritedBy, page, currentUser);
    }
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
    List<String> followdUsers = followedUsers(user.getId());
    if (followdUsers.size() == 0) {
      return new ArticleDataList(new ArrayList<>(), 0);
    } else if (routeArticleThroughPort()) {
      ArticleRowPage rows = articleQueryPort.findArticlesOfAuthors(followdUsers, page);
      List<ArticleData> articles = compose(rows.getArticles());
      fillExtraInfo(articles, user);
      return new ArticleDataList(articles, rows.getCount());
    } else {
      List<ArticleData> articles = articleReadService.findArticlesOfAuthors(followdUsers, page);
      fillExtraInfo(articles, user);
      int count = articleReadService.countFeedSize(followdUsers);
      return new ArticleDataList(articles, count);
    }
  }

  private boolean routeArticleThroughPort() {
    return articleQueryPort != null && articleQueryPort.ownsArticleReads();
  }

  /**
   * Public filter values are usernames; the Article port only knows ids. {@code author=} becomes
   * the author's user id and {@code favorited=} the id list of that user's favorites (Favorite
   * port). An unknown username short-circuits to an empty page without calling the port.
   */
  private static final class PortFilter {
    final String authorId;
    final List<String> ids;
    final boolean empty;

    PortFilter(String authorId, List<String> ids, boolean empty) {
      this.authorId = authorId;
      this.ids = ids;
      this.empty = empty;
    }
  }

  private PortFilter resolvePortFilter(String author, String favoritedBy) {
    String authorId = null;
    if (author != null) {
      UserData user = userByUsername(author);
      if (user == null) {
        return new PortFilter(null, null, true);
      }
      authorId = user.getId();
    }
    List<String> ids = null;
    if (favoritedBy != null) {
      ids = favoritedArticleIds(favoritedBy);
      if (ids.isEmpty()) {
        return new PortFilter(authorId, ids, true);
      }
    }
    return new PortFilter(authorId, ids, false);
  }

  private ArticleDataList findRecentArticlesViaPort(
      String tag, String author, String favoritedBy, Page page, User currentUser) {
    PortFilter filter = resolvePortFilter(author, favoritedBy);
    if (filter.empty) {
      return new ArticleDataList(new ArrayList<>(), 0);
    }
    ArticleIdPage idPage = articleQueryPort.queryArticleIds(tag, filter.authorId, filter.ids, page);
    if (idPage.getArticleIds().isEmpty()) {
      return new ArticleDataList(new ArrayList<>(), idPage.getCount());
    }
    List<ArticleData> articles = compose(articleQueryPort.findArticles(idPage.getArticleIds()));
    fillExtraInfo(articles, currentUser);
    return new ArticleDataList(articles, idPage.getCount());
  }

  private CursorPager<ArticleData> findRecentArticlesWithCursorViaPort(
      String tag,
      String author,
      String favoritedBy,
      CursorPageParameter<DateTime> page,
      User currentUser) {
    PortFilter filter = resolvePortFilter(author, favoritedBy);
    List<String> articleIds =
        filter.empty
            ? new ArrayList<>()
            : new ArrayList<>(
                articleQueryPort.queryArticleIdsWithCursor(tag, filter.authorId, filter.ids, page));
    if (articleIds.isEmpty()) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    }
    boolean hasExtra = articleIds.size() > page.getLimit();
    if (hasExtra) {
      articleIds.remove(page.getLimit());
    }
    if (!page.isNext()) {
      Collections.reverse(articleIds);
    }
    List<ArticleData> articles = compose(articleQueryPort.findArticles(articleIds));
    fillExtraInfo(articles, currentUser);
    return new CursorPager<>(articles, page.getDirection(), hasExtra);
  }

  private Optional<ArticleData> composeOne(Optional<ArticleRow> row, User user) {
    if (!row.isPresent()) {
      return Optional.empty();
    }
    ArticleData articleData = compose(Collections.singletonList(row.get())).get(0);
    if (user != null) {
      fillExtraInfo(articleData.getId(), user, articleData);
    }
    return Optional.of(articleData);
  }

  /**
   * Row + local author profile, mirroring {@code TransferData.xml#articleData}: {@code following}
   * starts {@code false}, {@code favorited}/{@code favoritesCount} are filled by the callers, and a
   * missing author yields a {@code null} profile like the {@code LEFT JOIN users}.
   */
  private List<ArticleData> compose(List<ArticleRow> rows) {
    List<ArticleData> result = new ArrayList<>(rows.size());
    if (rows.isEmpty()) {
      return result;
    }
    Map<String, UserData> users = new HashMap<>();
    for (UserData user :
        usersByIds(rows.stream().map(ArticleRow::getUserId).distinct().collect(toList()))) {
      users.put(user.getId(), user);
    }
    for (ArticleRow row : rows) {
      UserData author = users.get(row.getUserId());
      ProfileData profile =
          author == null
              ? null
              : new ProfileData(
                  author.getId(), author.getUsername(), author.getBio(), author.getImage(), false);
      result.add(
          new ArticleData(
              row.getId(),
              row.getSlug(),
              row.getTitle(),
              row.getDescription(),
              row.getBody(),
              false,
              0,
              row.getCreatedAt(),
              row.getUpdatedAt(),
              row.getTagList() == null ? new ArrayList<>() : new ArrayList<>(row.getTagList()),
              profile));
    }
    return result;
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

  /** Tags come from the Tag port only while the article rows themselves are still SQL joins. */
  private void setTagList(List<ArticleData> articles) {
    if (articles.isEmpty()
        || routeArticleThroughPort()
        || tagQueryPort == null
        || !tagQueryPort.ownsTagReads()) {
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
    UserData user = userByUsername(username);
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
        followingAuthors(
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
        .setFollowing(isFollowing(user.getId(), articleData.getProfileData().getId()));
  }

  private boolean routeUserThroughPort() {
    return userQueryPort != null && userQueryPort.ownsUserReads();
  }

  private boolean routeFollowThroughPort() {
    return followPort != null && followPort.ownsFollowReads();
  }

  private UserData userByUsername(String username) {
    if (routeUserThroughPort()) {
      return userQueryPort.findByUsername(username).orElse(null);
    }
    return userReadService.findByUsername(username);
  }

  private List<UserData> usersByIds(List<String> ids) {
    if (routeUserThroughPort()) {
      return userQueryPort.findByIds(ids);
    }
    return userReadService.findByIds(ids);
  }

  private boolean isFollowing(String userId, String targetId) {
    if (routeFollowThroughPort()) {
      return followPort.isFollowing(userId, targetId);
    }
    return userRelationshipQueryService.isUserFollowing(userId, targetId);
  }

  private Set<String> followingAuthors(String userId, List<String> ids) {
    if (routeFollowThroughPort()) {
      return followPort.followingAuthors(userId, ids);
    }
    return userRelationshipQueryService.followingAuthors(userId, ids);
  }

  private List<String> followedUsers(String userId) {
    if (routeFollowThroughPort()) {
      return followPort.followedUsers(userId);
    }
    return userRelationshipQueryService.followedUsers(userId);
  }
}
