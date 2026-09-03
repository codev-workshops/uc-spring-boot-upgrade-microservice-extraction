package io.spring.infrastructure.extraction.comment;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.comment.dto.CommentRowDto;
import io.spring.application.data.CommentData;
import io.spring.application.data.UserData;
import io.spring.infrastructure.mybatis.readservice.UserReadService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoteCommentQueryAdapterTest {
  private final CommentServiceClient client = mock(CommentServiceClient.class);
  private final UserReadService users = mock(UserReadService.class);
  private final RemoteCommentQueryAdapter adapter = new RemoteCommentQueryAdapter(client, users);

  private final CommentRowDto second =
      new CommentRowDto("c2", "second", "a", "u1", "2024-01-02T00:00:00.000Z", null);
  private final CommentRowDto first =
      new CommentRowDto("c1", "first", "a", "u2", "2024-01-01T00:00:00.000Z", null);
  private final CommentRowDto again =
      new CommentRowDto("c3", "again", "a", "u1", "2024-01-03T00:00:00.000Z", null);

  @Test
  public void composes_profiles_with_one_batched_lookup_in_monolith_insertion_order() {
    when(client.findByArticleId("a")).thenReturn(Arrays.asList(again, second, first));
    when(users.findByIds(Arrays.asList("u1", "u2")))
        .thenReturn(
            Arrays.asList(
                new UserData("u2", "b@x", "bob", "bio", "img"),
                new UserData("u1", "a@x", "alice", "", "")));

    List<CommentData> data = adapter.findByArticleId("a");

    verify(users, times(1)).findByIds(anyList());
    Assertions.assertEquals(Arrays.asList("c1", "c2", "c3"), ids(data));
    CommentData c2 = data.get(1);
    Assertions.assertEquals("second", c2.getBody());
    Assertions.assertEquals("a", c2.getArticleId());
    Assertions.assertEquals(1704153600000L, c2.getCreatedAt().getMillis());
    Assertions.assertEquals(c2.getCreatedAt(), c2.getUpdatedAt());
    Assertions.assertEquals("alice", c2.getProfileData().getUsername());
    Assertions.assertEquals("u1", c2.getProfileData().getId());
    Assertions.assertFalse(c2.getProfileData().isFollowing());
    Assertions.assertEquals("bob", data.get(0).getProfileData().getUsername());
    Assertions.assertEquals("bio", data.get(0).getProfileData().getBio());
    Assertions.assertEquals("img", data.get(0).getProfileData().getImage());
  }

  @Test
  public void cursor_pages_keep_the_service_order() {
    when(client.findByArticleIdWithCursor(
            org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Arrays.asList(second, first));
    when(users.findByIds(anyList())).thenReturn(Collections.emptyList());
    Assertions.assertEquals(
        Arrays.asList("c2", "c1"), ids(adapter.findByArticleIdWithCursor("a", null)));
  }

  @Test
  public void empty_list_skips_the_profile_lookup() {
    when(client.findByArticleId("a")).thenReturn(Collections.emptyList());
    Assertions.assertTrue(adapter.findByArticleId("a").isEmpty());
    verify(users, never()).findByIds(anyList());
  }

  @Test
  public void find_by_id_returns_null_when_the_service_has_no_row() {
    when(client.findById("missing")).thenReturn(Optional.empty());
    Assertions.assertNull(adapter.findById("missing"));
    verify(users, never()).findByIds(anyList());
  }

  @Test
  public void find_by_id_composes_a_single_row() {
    when(client.findById("c1")).thenReturn(Optional.of(first));
    when(users.findByIds(Collections.singletonList("u2")))
        .thenReturn(Collections.singletonList(new UserData("u2", "b@x", "bob", "", "")));

    CommentData data = adapter.findById("c1");

    Assertions.assertEquals("c1", data.getId());
    Assertions.assertEquals("bob", data.getProfileData().getUsername());
  }

  @Test
  public void unknown_author_yields_a_null_profile_like_the_left_join() {
    when(client.findByArticleId("a")).thenReturn(Collections.singletonList(first));
    when(users.findByIds(anyList())).thenReturn(Collections.emptyList());

    CommentData data = adapter.findByArticleId("a").get(0);
    Assertions.assertNull(data.getProfileData());
  }

  private static List<String> ids(List<CommentData> data) {
    return data.stream().map(CommentData::getId).collect(java.util.stream.Collectors.toList());
  }
}
