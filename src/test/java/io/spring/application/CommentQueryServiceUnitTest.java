package io.spring.application;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.CursorPager.Direction;
import io.spring.application.data.CommentData;
import io.spring.application.data.ProfileData;
import io.spring.application.readservice.CommentReadService;
import io.spring.application.readservice.UserRelationshipQueryService;
import io.spring.core.user.User;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CommentQueryServiceUnitTest {
  private CommentReadService commentReadService;
  private UserRelationshipQueryService userRelationshipQueryService;
  private CommentQueryService commentQueryService;
  private User user;

  @BeforeEach
  public void setUp() {
    commentReadService = mock(CommentReadService.class);
    userRelationshipQueryService = mock(UserRelationshipQueryService.class);
    commentQueryService = new CommentQueryService(commentReadService, userRelationshipQueryService);
    user = new User("john@jacob.com", "johnjacob", "123", "", "");
  }

  private CommentData comment(String id, String authorId) {
    DateTime time = new DateTime(2020, 1, 1, 0, 0);
    return new CommentData(
        id, "body", "article", time, time, new ProfileData(authorId, authorId, "", "", false));
  }

  private List<String> ids(List<CommentData> comments) {
    List<String> result = new ArrayList<>();
    comments.forEach(c -> result.add(c.getId()));
    return result;
  }

  @Test
  public void should_return_empty_when_comment_not_found() {
    when(commentReadService.findById(eq("missing"))).thenReturn(null);

    Assertions.assertFalse(commentQueryService.findById("missing", user).isPresent());
  }

  @Test
  public void should_set_following_flag_when_comment_found() {
    when(commentReadService.findById(eq("c1"))).thenReturn(comment("c1", "author"));
    when(userRelationshipQueryService.isUserFollowing(eq(user.getId()), eq("author")))
        .thenReturn(true);

    Optional<CommentData> result = commentQueryService.findById("c1", user);

    Assertions.assertTrue(result.isPresent());
    Assertions.assertTrue(result.get().getProfileData().isFollowing());
  }

  @Test
  public void should_mark_followed_comment_authors_for_current_user() {
    when(commentReadService.findByArticleId(eq("article")))
        .thenReturn(new ArrayList<>(Arrays.asList(comment("c1", "a"), comment("c2", "b"))));
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(new HashSet<>(Collections.singletonList("b")));

    List<CommentData> comments = commentQueryService.findByArticleId("article", user);

    Assertions.assertFalse(comments.get(0).getProfileData().isFollowing());
    Assertions.assertTrue(comments.get(1).getProfileData().isFollowing());
  }

  @Test
  public void should_not_query_relations_without_user_or_comments() {
    when(commentReadService.findByArticleId(eq("article")))
        .thenReturn(new ArrayList<>(Collections.singletonList(comment("c1", "a"))));
    when(commentReadService.findByArticleId(eq("empty"))).thenReturn(new ArrayList<>());

    Assertions.assertEquals(1, commentQueryService.findByArticleId("article", null).size());
    Assertions.assertTrue(commentQueryService.findByArticleId("empty", user).isEmpty());
    verify(userRelationshipQueryService, never()).followingAuthors(anyString(), anyList());
  }

  @Test
  public void should_return_empty_pager_when_article_has_no_comments() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.NEXT);
    when(commentReadService.findByArticleIdWithCursor(eq("article"), eq(page)))
        .thenReturn(new ArrayList<>());

    CursorPager<CommentData> pager =
        commentQueryService.findByArticleIdWithCursor("article", user, page);

    Assertions.assertTrue(pager.getData().isEmpty());
    Assertions.assertFalse(pager.hasNext());
  }

  @Test
  public void should_remove_extra_comment_and_mark_has_next() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 2, Direction.NEXT);
    when(commentReadService.findByArticleIdWithCursor(eq("article"), eq(page)))
        .thenReturn(
            new ArrayList<>(
                Arrays.asList(comment("c1", "a"), comment("c2", "b"), comment("c3", "a"))));
    when(userRelationshipQueryService.followingAuthors(eq(user.getId()), anyList()))
        .thenReturn(new HashSet<>(Collections.singletonList("a")));

    CursorPager<CommentData> pager =
        commentQueryService.findByArticleIdWithCursor("article", user, page);

    Assertions.assertEquals(Arrays.asList("c1", "c2"), ids(pager.getData()));
    Assertions.assertTrue(pager.hasNext());
    Assertions.assertTrue(pager.getData().get(0).getProfileData().isFollowing());
    Assertions.assertFalse(pager.getData().get(1).getProfileData().isFollowing());
  }

  @Test
  public void should_reverse_previous_comment_page_without_user() {
    CursorPageParameter<DateTime> page = new CursorPageParameter<>(null, 5, Direction.PREV);
    when(commentReadService.findByArticleIdWithCursor(eq("article"), eq(page)))
        .thenReturn(new ArrayList<>(Arrays.asList(comment("c2", "a"), comment("c1", "a"))));

    CursorPager<CommentData> pager =
        commentQueryService.findByArticleIdWithCursor("article", null, page);

    Assertions.assertEquals(Arrays.asList("c1", "c2"), ids(pager.getData()));
    Assertions.assertFalse(pager.hasPrevious());
    verify(userRelationshipQueryService, never()).followingAuthors(anyString(), anyList());
  }
}
