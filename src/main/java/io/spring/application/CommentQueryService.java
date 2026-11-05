package io.spring.application;

import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.application.data.UserData;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import io.spring.infrastructure.comments.CommentServiceClient;
import io.spring.infrastructure.comments.RemoteComment;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class CommentQueryService {
  private CommentServiceClient commentServiceClient;
  private UserReadService userReadService;
  private UserRelationshipQueryService userRelationshipQueryService;

  public CommentData fromSavedComment(Comment comment, User author) {
    return new CommentData(
        comment.getId(),
        comment.getBody(),
        comment.getArticleId(),
        comment.getCreatedAt(),
        comment.getCreatedAt(),
        new ProfileData(
            author.getId(),
            author.getUsername(),
            author.getBio(),
            author.getImage(),
            userRelationshipQueryService.isUserFollowing(author.getId(), author.getId())));
  }

  public List<CommentData> findByArticleId(String articleId, User user) {
    List<CommentData> comments = toCommentData(commentServiceClient.findByArticleId(articleId));
    if (comments.size() > 0 && user != null) {
      markFollowing(comments, user);
    }
    return comments;
  }

  public CursorPager<CommentData> findByArticleIdWithCursor(
      String articleId, User user, CursorPageParameter<DateTime> page) {
    List<CommentData> comments =
        toCommentData(commentServiceClient.findByArticleIdWithCursor(articleId, page));
    if (comments.isEmpty()) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    }
    if (user != null) {
      markFollowing(comments, user);
    }
    boolean hasExtra = comments.size() > page.getLimit();
    if (hasExtra) {
      comments.remove(page.getLimit());
    }
    if (!page.isNext()) {
      Collections.reverse(comments);
    }
    return new CursorPager<>(comments, page.getDirection(), hasExtra);
  }

  private void markFollowing(List<CommentData> comments, User user) {
    Set<String> followingAuthors =
        userRelationshipQueryService.followingAuthors(
            user.getId(),
            comments.stream()
                .map(commentData -> commentData.getProfileData().getId())
                .collect(Collectors.toList()));
    comments.forEach(
        commentData -> {
          if (followingAuthors.contains(commentData.getProfileData().getId())) {
            commentData.getProfileData().setFollowing(true);
          }
        });
  }

  private List<CommentData> toCommentData(List<RemoteComment> remoteComments) {
    Map<String, ProfileData> profiles = new HashMap<>();
    return remoteComments.stream()
        .map(remote -> toCommentData(remote, profiles))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private CommentData toCommentData(RemoteComment remote, Map<String, ProfileData> profiles) {
    ProfileData author = profiles.computeIfAbsent(remote.getUserId(), this::loadProfile);
    return new CommentData(
        remote.getId(),
        remote.getBody(),
        remote.getArticleId(),
        remote.createdAtTime(),
        remote.updatedAtTime(),
        new ProfileData(
            author.getId(), author.getUsername(), author.getBio(), author.getImage(), false));
  }

  private ProfileData loadProfile(String userId) {
    UserData userData = userReadService.findById(userId);
    if (userData == null) {
      return new ProfileData(userId, null, null, null, false);
    }
    return new ProfileData(
        userData.getId(), userData.getUsername(), userData.getBio(), userData.getImage(), false);
  }
}
