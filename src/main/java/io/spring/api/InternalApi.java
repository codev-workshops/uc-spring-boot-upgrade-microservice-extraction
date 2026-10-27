package io.spring.api;

import io.spring.application.InternalQueryService;
import io.spring.core.user.User;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only endpoints consumed by extracted services such as article-service. */
@RestController
@RequestMapping(path = "/internal")
@AllArgsConstructor
public class InternalApi {
  private InternalQueryService internalQueryService;

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> currentUser(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(
        Map.of("user", Map.of("id", user.getId(), "username", user.getUsername())));
  }

  @GetMapping("/me/following")
  public ResponseEntity<Map<String, Object>> following(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(Map.of("userIds", internalQueryService.followedUserIds(user)));
  }

  @GetMapping("/profiles")
  public ResponseEntity<Map<String, Object>> profiles(
      @RequestParam(value = "ids", required = false) List<String> ids,
      @RequestParam(value = "username", required = false) String username,
      @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(
        Map.of("profiles", internalQueryService.profiles(ids, username, user)));
  }

  @GetMapping("/favorites")
  public ResponseEntity<Map<String, Object>> favorites(
      @RequestParam("articleIds") List<String> articleIds, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(Map.of("favorites", internalQueryService.favorites(articleIds, user)));
  }

  @GetMapping("/favorites/by-user/{username}")
  public ResponseEntity<Map<String, Object>> favoritedBy(
      @PathVariable("username") String username) {
    return ResponseEntity.ok(
        Map.of("articleIds", internalQueryService.articleIdsFavoritedBy(username)));
  }
}
