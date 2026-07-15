package io.spring.application.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.CommentQueryService;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.client.UserServiceClient;
import io.spring.client.dto.ProfileResponse;
import io.spring.infrastructure.mybatis.readservice.CommentReadService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

public class CommentQueryServiceTest {
  private CommentReadService commentReadService;
  private UserServiceClient userServiceClient;
  private CommentQueryService commentQueryService;

  @BeforeEach
  public void setUp() {
    commentReadService = mock(CommentReadService.class);
    userServiceClient = mock(UserServiceClient.class);
    commentQueryService = new CommentQueryService(commentReadService, userServiceClient);
  }

  @Test
  public void shouldHydrateDistinctAuthorsWithOneBatchCall() {
    List<CommentData> comments =
        Arrays.asList(comment("comment-1", "user-1"), comment("comment-2", "user-1"));
    when(commentReadService.findByArticleId("article-1")).thenReturn(comments);
    when(userServiceClient.findProfiles("viewer-1", Collections.singletonList("user-1")))
        .thenReturn(
            Collections.singletonList(
                new ProfileResponse("user-1", "author", "bio", "image", true)));

    List<CommentData> result = commentQueryService.findByArticleId("article-1", "viewer-1");

    verify(userServiceClient).findProfiles(eq("viewer-1"), eq(Collections.singletonList("user-1")));
    assertEquals("author", result.get(0).getProfileData().getUsername());
    assertEquals(true, result.get(1).getProfileData().isFollowing());
  }

  @Test
  public void shouldUsePlaceholderProfilesWhenBatchCallFails() {
    List<CommentData> comments = Collections.singletonList(comment("comment-1", "user-1"));
    when(commentReadService.findByArticleId("article-1")).thenReturn(comments);
    when(userServiceClient.findProfiles("viewer-1", Collections.singletonList("user-1")))
        .thenThrow(new ResourceAccessException("unreachable"));

    List<CommentData> result = commentQueryService.findByArticleId("article-1", "viewer-1");

    assertEquals("", result.get(0).getProfileData().getUsername());
    assertFalse(result.get(0).getProfileData().isFollowing());
  }

  private CommentData comment(String id, String userId) {
    DateTime now = new DateTime();
    return new CommentData(
        id, "content", "article-1", now, now, new ProfileData(userId, null, null, null, false));
  }
}
