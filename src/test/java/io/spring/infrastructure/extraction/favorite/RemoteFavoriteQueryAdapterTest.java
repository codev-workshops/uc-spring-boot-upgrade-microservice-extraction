package io.spring.infrastructure.extraction.favorite;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.spring.application.data.ArticleFavoriteCount;
import io.spring.application.favorite.dto.FavoriteCountDto;
import io.spring.application.favorite.dto.UserFavoritesDto;
import io.spring.core.user.User;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class RemoteFavoriteQueryAdapterTest {
  private final FavoriteServiceClient client = mock(FavoriteServiceClient.class);
  private final RemoteFavoriteQueryAdapter adapter = new RemoteFavoriteQueryAdapter(client);
  private final User user = new User("reader@test.com", "reader", "123", "", "");

  @Test
  public void counts_fill_missing_ids_with_zero_in_request_order() {
    when(client.counts(anyList()))
        .thenReturn(Collections.singletonList(new FavoriteCountDto("b", 3)));

    List<ArticleFavoriteCount> counts = adapter.articlesFavoriteCount(Arrays.asList("a", "b", "c"));

    Assertions.assertEquals(
        Arrays.asList(
            new ArticleFavoriteCount("a", 0),
            new ArticleFavoriteCount("b", 3),
            new ArticleFavoriteCount("c", 0)),
        counts);
  }

  @Test
  public void empty_batches_never_reach_the_client() {
    Assertions.assertTrue(adapter.articlesFavoriteCount(new ArrayList<>()).isEmpty());
    Assertions.assertTrue(adapter.userFavorites(new ArrayList<>(), user).isEmpty());
    verify(client, never()).counts(anyList());
    verify(client, never()).userFavorites(any(), anyList());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void batches_above_500_ids_are_chunked() {
    List<String> ids =
        IntStream.range(0, 600).mapToObj(i -> "id-" + i).collect(Collectors.toList());
    when(client.counts(anyList())).thenReturn(Collections.emptyList());
    when(client.userFavorites(eq(user.getId()), anyList()))
        .thenAnswer(
            invocation ->
                new UserFavoritesDto(
                    user.getId(),
                    Collections.singletonList(((List<String>) invocation.getArgument(1)).get(0))));

    List<ArticleFavoriteCount> counts = adapter.articlesFavoriteCount(ids);
    Set<String> favorites = adapter.userFavorites(ids, user);

    ArgumentCaptor<List<String>> chunks = ArgumentCaptor.forClass(List.class);
    verify(client, times(2)).counts(chunks.capture());
    Assertions.assertEquals(Arrays.asList(500, 100), sizes(chunks.getAllValues()));
    Assertions.assertEquals(600, counts.size());
    Assertions.assertTrue(counts.stream().allMatch(c -> c.getCount() == 0));
    verify(client, times(2)).userFavorites(eq(user.getId()), anyList());
    Assertions.assertEquals(new java.util.HashSet<>(Arrays.asList("id-0", "id-500")), favorites);
  }

  @Test
  public void single_article_helpers_use_the_batch_endpoints() {
    when(client.counts(Collections.singletonList("a"))).thenReturn(Collections.emptyList());
    when(client.userFavorites(user.getId(), Collections.singletonList("a")))
        .thenReturn(new UserFavoritesDto(user.getId(), Collections.singletonList("a")));

    Assertions.assertEquals(0, adapter.articleFavoriteCount("a"));
    Assertions.assertTrue(adapter.isUserFavorite(user.getId(), "a"));
    Assertions.assertTrue(adapter.ownsFavoritedByFilter());
  }

  private static List<Integer> sizes(List<List<String>> lists) {
    return lists.stream().map(List::size).collect(Collectors.toList());
  }
}
