package io.spring.application.comment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.CommentQueryService;
import io.spring.application.CursorPageParameter;
import io.spring.application.CursorPager;
import io.spring.application.CursorPager.Direction;
import io.spring.application.data.CommentData;
import io.spring.application.data.UserData;
import io.spring.core.comment.Comment;
import io.spring.core.user.User;
import io.spring.infrastructure.comments.CommentServiceClient;
import io.spring.infrastructure.comments.RemoteComment;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import io.spring.infrastructure.mybatis.readservice.UserRelationshipQueryService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class CommentQueryServiceTest {
  private CommentServiceClient commentServiceClient;
  private UserReadService userReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private CommentQueryService commentQueryService;

  private User user;
  private UserData author;

  @BeforeEach
  public void setUp() {
    commentServiceClient = Mockito.mock(CommentServiceClient.class);
    userReadService = Mockito.mock(UserReadService.class);
    userRelationshipQueryService = Mockito.mock(UserRelationshipQueryService.class);
    commentQueryService =
        new CommentQueryService(
            commentServiceClient, userReadService, userRelationshipQueryService);

    user = new User("aisensiy@test.com", "aisensiy", "123", "", "");
    author = new UserData("author-id", "author@test.com", "author", "bio", "image");
    when(userReadService.findById(eq(author.getId()))).thenReturn(author);
  }

  private RemoteComment remoteComment(String id, String body, String userId, DateTime createdAt) {
    String time = createdAt.toString();
    return new RemoteComment(id, body, "article-id", userId, time, time);
  }

  @Test
  public void should_build_saved_comment_without_calling_comments_service() {
    DateTime createdAt = new DateTime(1000L);
    Comment saved = new Comment("c1", "content", user.getId(), "article-id", createdAt);

    CommentData commentData = commentQueryService.fromSavedComment(saved, user);

    Assertions.assertEquals("c1", commentData.getId());
    Assertions.assertEquals("content", commentData.getBody());
    Assertions.assertEquals("article-id", commentData.getArticleId());
    Assertions.assertEquals(createdAt.getMillis(), commentData.getCreatedAt().getMillis());
    Assertions.assertEquals(user.getUsername(), commentData.getProfileData().getUsername());
    Assertions.assertFalse(commentData.getProfileData().isFollowing());
    verifyNoInteractions(commentServiceClient);
  }

  @Test
  public void should_read_comments_of_article() {
    UserData user2 = new UserData("user2", "user2@email.com", "user2", "", "");
    when(userReadService.findById(eq(user2.getId()))).thenReturn(user2);
    when(commentServiceClient.findByArticleId(eq("article-id")))
        .thenReturn(
            Arrays.asList(
                remoteComment("c1", "content1", author.getId(), new DateTime()),
                remoteComment("c2", "content2", user2.getId(), new DateTime()),
                remoteComment("c3", "content3", author.getId(), new DateTime())));
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(Collections.singleton(user2.getId()));

    List<CommentData> comments = commentQueryService.findByArticleId("article-id", user);
    Assertions.assertEquals(3, comments.size());
    Assertions.assertFalse(comments.get(0).getProfileData().isFollowing());
    Assertions.assertTrue(comments.get(1).getProfileData().isFollowing());
    Assertions.assertEquals("author", comments.get(2).getProfileData().getUsername());
    verify(userReadService, times(1)).findById(eq(author.getId()));
  }

  @Test
  public void should_use_placeholder_profile_for_unknown_author() {
    when(commentServiceClient.findByArticleId(eq("article-id")))
        .thenReturn(Arrays.asList(remoteComment("c1", "content", "ghost", new DateTime())));

    List<CommentData> comments = commentQueryService.findByArticleId("article-id", null);
    Assertions.assertEquals(1, comments.size());
    Assertions.assertEquals("ghost", comments.get(0).getProfileData().getId());
    Assertions.assertNull(comments.get(0).getProfileData().getUsername());
  }

  @Test
  public void should_page_comments_with_cursor() {
    when(commentServiceClient.findByArticleIdWithCursor(eq("article-id"), any()))
        .thenReturn(
            Arrays.asList(
                remoteComment("c3", "content3", author.getId(), new DateTime(3000L)),
                remoteComment("c2", "content2", author.getId(), new DateTime(2000L)),
                remoteComment("c1", "content1", author.getId(), new DateTime(1000L))));

    CursorPager<CommentData> page =
        commentQueryService.findByArticleIdWithCursor(
            "article-id", null, new CursorPageParameter<>(null, 2, Direction.NEXT));
    Assertions.assertEquals(2, page.getData().size());
    Assertions.assertEquals("c3", page.getData().get(0).getId());
    Assertions.assertTrue(page.hasNext());
  }

  @Test
  public void should_return_empty_page_when_no_comments() {
    when(commentServiceClient.findByArticleIdWithCursor(eq("article-id"), any()))
        .thenReturn(Collections.emptyList());

    CursorPager<CommentData> page =
        commentQueryService.findByArticleIdWithCursor(
            "article-id", user, new CursorPageParameter<>(null, 2, Direction.PREV));
    Assertions.assertTrue(page.getData().isEmpty());
    Assertions.assertFalse(page.hasPrevious());
  }
}
