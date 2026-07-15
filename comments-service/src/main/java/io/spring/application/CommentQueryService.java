package io.spring.application;

import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.client.UserServiceClient;
import io.spring.client.dto.ProfileResponse;
import io.spring.infrastructure.mybatis.readservice.CommentReadService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@AllArgsConstructor
public class CommentQueryService {
  private CommentReadService commentReadService;
  private UserServiceClient userServiceClient;

  public Optional<CommentData> findById(String id, String viewerId) {
    CommentData commentData = commentReadService.findById(id);
    if (commentData == null) {
      return Optional.empty();
    }
    hydrateProfiles(Collections.singletonList(commentData), viewerId);
    return Optional.of(commentData);
  }

  public List<CommentData> findByArticleId(String articleId, String viewerId) {
    List<CommentData> comments = commentReadService.findByArticleId(articleId);
    hydrateProfiles(comments, viewerId);
    return comments;
  }

  public CursorPager<CommentData> findByArticleIdWithCursor(
      String articleId, String viewerId, CursorPageParameter<DateTime> page) {
    List<CommentData> comments = commentReadService.findByArticleIdWithCursor(articleId, page);
    if (comments.isEmpty()) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    }
    boolean hasExtra = comments.size() > page.getLimit();
    if (hasExtra) {
      comments.remove(page.getLimit());
    }
    if (!page.isNext()) {
      Collections.reverse(comments);
    }
    hydrateProfiles(comments, viewerId);
    return new CursorPager<>(comments, page.getDirection(), hasExtra);
  }

  private void hydrateProfiles(List<CommentData> comments, String viewerId) {
    if (comments.isEmpty()) {
      return;
    }
    Set<String> authorIds =
        comments.stream()
            .map(comment -> comment.getProfileData().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, ProfileResponse> profiles;
    try {
      profiles =
          userServiceClient.findProfiles(viewerId, new ArrayList<>(authorIds)).stream()
              .collect(Collectors.toMap(ProfileResponse::getId, Function.identity()));
    } catch (RestClientException e) {
      profiles = Collections.emptyMap();
    }
    for (CommentData comment : comments) {
      String authorId = comment.getProfileData().getId();
      ProfileResponse profile = profiles.get(authorId);
      comment.setProfileData(
          profile == null
              ? new ProfileData(authorId, "", "", "", false)
              : new ProfileData(
                  profile.getId(),
                  profile.getUsername(),
                  profile.getBio(),
                  profile.getImage(),
                  profile.isFollowing()));
    }
  }
}
