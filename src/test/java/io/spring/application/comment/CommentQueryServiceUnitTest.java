package io.spring.application.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.CommentQueryService;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.core.user.User;
import io.spring.infrastructure.mybatis.readservice.CommentReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class CommentQueryServiceUnitTest {
  @Mock private CommentReadService commentReadService;
  @Mock private UserRelationshipQueryService userRelationshipQueryService;

  private CommentQueryService commentQueryService;
  private User currentUser;

  @BeforeEach
  public void setUp() {
    commentQueryService = new CommentQueryService(commentReadService, userRelationshipQueryService);
    currentUser = new User("current@example.com", "current", "password", "", "");
  }

  @Test
  public void findByIdReturnsEmptyWithoutRelationshipLookupWhenMissing() {
    when(commentReadService.findById("missing")).thenReturn(null);

    assertEquals(Optional.empty(), commentQueryService.findById("missing", currentUser));
    verifyNoInteractions(userRelationshipQueryService);
  }

  @Test
  public void findByIdSetsFollowingForPresentComment() {
    CommentData comment = comment("comment-id", "author-id");
    when(commentReadService.findById("comment-id")).thenReturn(comment);
    when(userRelationshipQueryService.isUserFollowing(currentUser.getId(), "author-id"))
        .thenReturn(true);

    Optional<CommentData> result = commentQueryService.findById("comment-id", currentUser);

    assertTrue(result.isPresent());
    assertTrue(result.get().getProfileData().isFollowing());
  }

  @Test
  public void findByArticleIdDoesNotLookupRelationshipsForEmptyResults() {
    when(commentReadService.findByArticleId("article-id")).thenReturn(Collections.emptyList());

    assertTrue(commentQueryService.findByArticleId("article-id", currentUser).isEmpty());
    verifyNoInteractions(userRelationshipQueryService);
  }

  @Test
  public void findByArticleIdWithoutUserLeavesFollowingFalse() {
    CommentData first = comment("first", "author-1");
    CommentData second = comment("second", "author-2");
    when(commentReadService.findByArticleId("article-id")).thenReturn(Arrays.asList(first, second));

    var comments = commentQueryService.findByArticleId("article-id", null);

    assertFalse(comments.get(0).getProfileData().isFollowing());
    assertFalse(comments.get(1).getProfileData().isFollowing());
    verifyNoInteractions(userRelationshipQueryService);
  }

  @Test
  public void findByArticleIdMarksOnlyFollowedAuthors() {
    CommentData first = comment("first", "author-1");
    CommentData second = comment("second", "author-2");
    when(commentReadService.findByArticleId("article-id")).thenReturn(Arrays.asList(first, second));
    when(userRelationshipQueryService.followingAuthors(
            currentUser.getId(), Arrays.asList("author-1", "author-2")))
        .thenReturn(Set.of("author-1"));

    var comments = commentQueryService.findByArticleId("article-id", currentUser);

    assertTrue(comments.get(0).getProfileData().isFollowing());
    assertFalse(comments.get(1).getProfileData().isFollowing());
  }

  private CommentData comment(String id, String authorId) {
    return new CommentData(
        id,
        "body",
        "article-id",
        new DateTime(),
        new DateTime(),
        new ProfileData(authorId, authorId, "bio", "image", false));
  }
}
