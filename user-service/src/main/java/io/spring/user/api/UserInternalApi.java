package io.spring.user.api;

import io.spring.user.api.exception.InvalidAuthenticationException;
import io.spring.user.api.exception.NoAuthorizationException;
import io.spring.user.api.exception.ResourceNotFoundException;
import io.spring.user.application.UserCommandService;
import io.spring.user.application.UserCommandService.CreateResult;
import io.spring.user.application.UserQueryService;
import io.spring.user.application.data.UserData;
import io.spring.user.core.user.User;
import io.spring.user.core.user.UserUpdate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the monolith's RemoteUser* adapters (phase-5-user.md §2.1). */
@RestController
@RequestMapping(path = "/internal/users")
public class UserInternalApi {
  private final UserQueryService userQueryService;
  private final UserCommandService userCommandService;

  public UserInternalApi(UserQueryService userQueryService, UserCommandService userCommandService) {
    this.userQueryService = userQueryService;
    this.userCommandService = userCommandService;
  }

  @GetMapping(path = "/{id}")
  public ResponseEntity<Map<String, UserData>> getById(@PathVariable String id) {
    return userEnvelope(userQueryService.findById(id).orElseThrow(ResourceNotFoundException::new));
  }

  @GetMapping(path = "/by-username/{username}")
  public ResponseEntity<Map<String, UserData>> getByUsername(@PathVariable String username) {
    return userEnvelope(
        userQueryService.findByUsername(username).orElseThrow(ResourceNotFoundException::new));
  }

  @GetMapping(path = "/by-email/{email}")
  public ResponseEntity<Map<String, UserData>> getByEmail(@PathVariable String email) {
    return userEnvelope(
        userQueryService.findByEmail(email).orElseThrow(ResourceNotFoundException::new));
  }

  @GetMapping
  public ResponseEntity<Map<String, List<UserData>>> getByIds(
      @RequestParam(value = "ids", required = false) String ids) {
    return ResponseEntity.ok(
        Collections.singletonMap("users", userQueryService.findByIds(splitIds(ids))));
  }

  @PostMapping
  public ResponseEntity<Map<String, UserData>> create(@Valid @RequestBody NewUserRequest request) {
    CreateResult result =
        userCommandService.create(
            new User(
                request.getId(),
                request.getUsername(),
                request.getEmail(),
                request.getPasswordHash(),
                request.getBio(),
                request.getImage()));
    return ResponseEntity.status(result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(Collections.singletonMap("user", result.getUser()));
  }

  @PutMapping(path = "/{id}")
  public ResponseEntity<Map<String, UserData>> update(
      @PathVariable String id,
      @RequestBody UpdateUserRequest request,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(id, currentUserId);
    return userEnvelope(
        userCommandService.update(
            new UserUpdate(
                id,
                request.getUsername(),
                request.getEmail(),
                request.getPasswordHash(),
                request.getBio(),
                request.getImage())));
  }

  @PostMapping(path = "/{id}/credentials/verify")
  public ResponseEntity<Map<String, Boolean>> verifyCredentials(
      @PathVariable String id, @RequestBody VerifyCredentialsRequest request) {
    return ResponseEntity.ok(
        Collections.singletonMap(
            "valid", userCommandService.verifyCredentials(id, request.getPassword())));
  }

  @GetMapping(path = "/{id}/following")
  public ResponseEntity<Map<String, List<String>>> following(
      @PathVariable String id, @RequestParam(value = "ids", required = false) String ids) {
    return ResponseEntity.ok(
        Collections.singletonMap(
            "followingIds", userQueryService.followingAuthors(id, splitIds(ids))));
  }

  @GetMapping(path = "/{id}/followed")
  public ResponseEntity<Map<String, List<String>>> followed(@PathVariable String id) {
    return ResponseEntity.ok(
        Collections.singletonMap("followedIds", userQueryService.followedUsers(id)));
  }

  @GetMapping(path = "/{id}/follows/{targetId}")
  public ResponseEntity<Map<String, Boolean>> isFollowing(
      @PathVariable String id, @PathVariable String targetId) {
    return ResponseEntity.ok(
        Collections.singletonMap("following", userQueryService.isFollowing(id, targetId)));
  }

  @PutMapping(path = "/{id}/follows/{targetId}")
  public ResponseEntity<Void> follow(
      @PathVariable String id,
      @PathVariable String targetId,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(id, currentUserId);
    userCommandService.follow(id, targetId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping(path = "/{id}/follows/{targetId}")
  public ResponseEntity<Void> unfollow(
      @PathVariable String id,
      @PathVariable String targetId,
      @AuthenticationPrincipal String currentUserId) {
    checkOwner(id, currentUserId);
    userCommandService.unfollow(id, targetId);
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<Map<String, UserData>> userEnvelope(UserData user) {
    return ResponseEntity.ok(Collections.singletonMap("user", user));
  }

  private static List<String> splitIds(String ids) {
    if (ids == null || ids.trim().isEmpty()) {
      return Collections.emptyList();
    }
    return Arrays.stream(ids.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private static void checkOwner(String userId, String currentUserId) {
    if (currentUserId == null) {
      throw new InvalidAuthenticationException();
    }
    if (!currentUserId.equals(userId)) {
      throw new NoAuthorizationException();
    }
  }
}
