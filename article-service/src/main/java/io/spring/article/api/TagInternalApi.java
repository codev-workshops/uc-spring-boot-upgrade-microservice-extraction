package io.spring.article.api;

import io.spring.article.api.exception.InvalidAuthenticationException;
import io.spring.article.application.TagCommandService;
import io.spring.article.application.TagQueryService;
import io.spring.article.application.data.ArticleTagsData;
import io.spring.article.core.tag.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the monolith's RemoteTag* adapters (phase-3-tag.md §2.1). */
@RestController
@RequestMapping(path = "/internal")
public class TagInternalApi {
  private final TagQueryService tagQueryService;
  private final TagCommandService tagCommandService;

  public TagInternalApi(TagQueryService tagQueryService, TagCommandService tagCommandService) {
    this.tagQueryService = tagQueryService;
    this.tagCommandService = tagCommandService;
  }

  @GetMapping(path = "/tags")
  public ResponseEntity<Map<String, List<String>>> allTags() {
    return ResponseEntity.ok(Collections.singletonMap("tags", tagQueryService.allTags()));
  }

  @GetMapping(path = "/articles/tags")
  public ResponseEntity<Map<String, List<ArticleTagsData>>> articleTags(
      @RequestParam(value = "articleIds", required = false, defaultValue = "") String articleIds) {
    return ResponseEntity.ok(
        Collections.singletonMap(
            "articleTags", tagQueryService.findArticleTags(parseIds(articleIds))));
  }

  @GetMapping(path = "/tags/{name}/article-ids")
  public ResponseEntity<Map<String, List<String>>> articleIdsByTag(@PathVariable String name) {
    return ResponseEntity.ok(
        Collections.singletonMap("articleIds", tagQueryService.findArticleIdsByTagName(name)));
  }

  @PutMapping(path = "/articles/{articleId}/tags")
  public ResponseEntity<ArticleTagsData> setTags(
      @PathVariable String articleId,
      @Valid @RequestBody PutArticleTagsRequest request,
      @AuthenticationPrincipal String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    List<Tag> tags =
        request.getTags().stream()
            .map(t -> new Tag(t.getId(), t.getName()))
            .collect(Collectors.toList());
    return ResponseEntity.ok(tagCommandService.setTags(articleId, tags));
  }

  private static List<String> parseIds(String articleIds) {
    if (articleIds == null || articleIds.trim().isEmpty()) {
      return new ArrayList<>();
    }
    return Arrays.stream(articleIds.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
