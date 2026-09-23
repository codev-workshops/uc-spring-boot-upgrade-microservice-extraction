package io.spring.api;

import io.spring.application.ArticleQueryService;
import io.spring.application.CursorPager;
import io.spring.application.Page;
import io.spring.application.article.ArticleCommandService;
import io.spring.application.article.NewArticleParam;
import io.spring.application.data.ArticleData;
import io.spring.core.article.Article;
import io.spring.core.user.User;
import java.util.HashMap;
import javax.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/articles")
@AllArgsConstructor
public class ArticlesApi {
  private ArticleCommandService articleCommandService;
  private ArticleQueryService articleQueryService;

  @PostMapping
  public ResponseEntity createArticle(
      @Valid @RequestBody NewArticleParam newArticleParam, @AuthenticationPrincipal User user) {
    Article article = articleCommandService.createArticle(newArticleParam, user);
    return ResponseEntity.ok(
        new HashMap<String, Object>() {
          {
            put("article", articleQueryService.findById(article.getId(), user).get());
          }
        });
  }

  @GetMapping(path = "feed", params = "first")
  public ResponseEntity getFeedNextPage(
      @RequestParam("first") int first,
      @RequestParam(value = "after", required = false) String after,
      @AuthenticationPrincipal User user) {
    return getFeedWithCursor(first, after, null, null, user);
  }

  @GetMapping(
      path = "feed",
      params = {"last", "!first"})
  public ResponseEntity getFeedPreviousPage(
      @RequestParam("last") int last,
      @RequestParam(value = "before", required = false) String before,
      @AuthenticationPrincipal User user) {
    return getFeedWithCursor(null, null, last, before, user);
  }

  @GetMapping(path = "feed")
  public ResponseEntity getFeed(
      @RequestParam(value = "offset", defaultValue = "0") int offset,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(articleQueryService.findUserFeed(user, new Page(offset, limit)));
  }

  @GetMapping(params = "first")
  public ResponseEntity getArticlesNextPage(
      @RequestParam("first") int first,
      @RequestParam(value = "after", required = false) String after,
      @RequestParam(value = "tag", required = false) String tag,
      @RequestParam(value = "favorited", required = false) String favoritedBy,
      @RequestParam(value = "author", required = false) String author,
      @AuthenticationPrincipal User user) {
    return getArticlesWithCursor(first, after, null, null, tag, author, favoritedBy, user);
  }

  @GetMapping(params = {"last", "!first"})
  public ResponseEntity getArticlesPreviousPage(
      @RequestParam("last") int last,
      @RequestParam(value = "before", required = false) String before,
      @RequestParam(value = "tag", required = false) String tag,
      @RequestParam(value = "favorited", required = false) String favoritedBy,
      @RequestParam(value = "author", required = false) String author,
      @AuthenticationPrincipal User user) {
    return getArticlesWithCursor(null, null, last, before, tag, author, favoritedBy, user);
  }

  @GetMapping
  public ResponseEntity getArticles(
      @RequestParam(value = "offset", defaultValue = "0") int offset,
      @RequestParam(value = "limit", defaultValue = "20") int limit,
      @RequestParam(value = "tag", required = false) String tag,
      @RequestParam(value = "favorited", required = false) String favoritedBy,
      @RequestParam(value = "author", required = false) String author,
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(
        articleQueryService.findRecentArticles(
            tag, author, favoritedBy, new Page(offset, limit), user));
  }

  private ResponseEntity getFeedWithCursor(
      Integer first, String after, Integer last, String before, User user) {
    CursorPager<ArticleData> articles =
        articleQueryService.findUserFeedWithCursor(
            user, CursorPageResponse.pageParameter(first, after, last, before));
    return ResponseEntity.ok(CursorPageResponse.of("articles", articles));
  }

  private ResponseEntity getArticlesWithCursor(
      Integer first,
      String after,
      Integer last,
      String before,
      String tag,
      String author,
      String favoritedBy,
      User user) {
    CursorPager<ArticleData> articles =
        articleQueryService.findRecentArticlesWithCursor(
            tag,
            author,
            favoritedBy,
            CursorPageResponse.pageParameter(first, after, last, before),
            user);
    return ResponseEntity.ok(CursorPageResponse.of("articles", articles));
  }
}
