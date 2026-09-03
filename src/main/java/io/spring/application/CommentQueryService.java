package io.spring.application;

import io.spring.application.comment.CommentQueryPort;
import io.spring.application.data.CommentData;
import io.spring.application.user.FollowPort;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommentQueryService {
  private final CommentQueryPort commentQueryPort;
  private final UserRelationshipQueryService userRelationshipQueryService;
  private final FollowPort followPort;

  @Autowired
  public CommentQueryService(
      CommentQueryPort commentQueryPort,
      UserRelationshipQueryService userRelationshipQueryService,
      ObjectProvider<FollowPort> followPort) {
    this(commentQueryPort, userRelationshipQueryService, followPort.getIfAvailable());
  }

  public CommentQueryService(
      CommentQueryPort commentQueryPort,
      UserRelationshipQueryService userRelationshipQueryService) {
    this(commentQueryPort, userRelationshipQueryService, (FollowPort) null);
  }

  public CommentQueryService(
      CommentQueryPort commentQueryPort,
      UserRelationshipQueryService userRelationshipQueryService,
      FollowPort followPort) {
    this.commentQueryPort = commentQueryPort;
    this.userRelationshipQueryService = userRelationshipQueryService;
    this.followPort = followPort;
  }

  public Optional<CommentData> findById(String id, User user) {
    CommentData commentData = commentQueryPort.findById(id);
    if (commentData == null) {
      return Optional.empty();
    } else {
      commentData
          .getProfileData()
          .setFollowing(isFollowing(user.getId(), commentData.getProfileData().getId()));
    }
    return Optional.ofNullable(commentData);
  }

  public List<CommentData> findByArticleId(String articleId, User user) {
    List<CommentData> comments = commentQueryPort.findByArticleId(articleId);
    if (comments.size() > 0 && user != null) {
      Set<String> followingAuthors =
          followingAuthors(
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
    return comments;
  }

  public CursorPager<CommentData> findByArticleIdWithCursor(
      String articleId, User user, CursorPageParameter<DateTime> page) {
    List<CommentData> comments = commentQueryPort.findByArticleIdWithCursor(articleId, page);
    if (comments.isEmpty()) {
      return new CursorPager<>(new ArrayList<>(), page.getDirection(), false);
    }
    if (user != null) {
      Set<String> followingAuthors =
          followingAuthors(
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
    boolean hasExtra = comments.size() > page.getLimit();
    if (hasExtra) {
      comments.remove(page.getLimit());
    }
    if (!page.isNext()) {
      Collections.reverse(comments);
    }
    return new CursorPager<>(comments, page.getDirection(), hasExtra);
  }

  private boolean isFollowing(String userId, String targetId) {
    if (followPort != null && followPort.ownsFollowReads()) {
      return followPort.isFollowing(userId, targetId);
    }
    return userRelationshipQueryService.isUserFollowing(userId, targetId);
  }

  private Set<String> followingAuthors(String userId, List<String> ids) {
    if (followPort != null && followPort.ownsFollowReads()) {
      return followPort.followingAuthors(userId, ids);
    }
    return userRelationshipQueryService.followingAuthors(userId, ids);
  }
}
