package io.spring.infrastructure.extraction.tag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.data.ArticleTagList;
import io.spring.application.tag.dto.ArticleTagsRowDto;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoteTagQueryAdapterTest {
  private final ArticleServiceClient client = mock(ArticleServiceClient.class);
  private final RemoteTagQueryAdapter adapter = new RemoteTagQueryAdapter(client);

  @Test
  public void one_entry_per_requested_id_in_request_order_with_missing_rows_empty() {
    when(client.tagsByArticleIds(Arrays.asList("a", "b", "c")))
        .thenReturn(
            Arrays.asList(
                new ArticleTagsRowDto("c", Arrays.asList("spring", "java")),
                new ArticleTagsRowDto("a", Collections.singletonList("java")),
                new ArticleTagsRowDto("zzz", Collections.singletonList("ignored"))));

    List<ArticleTagList> result = adapter.tagsByArticleIds(Arrays.asList("a", "b", "a", "c"));

    Assertions.assertEquals(3, result.size());
    Assertions.assertEquals("a", result.get(0).getArticleId());
    Assertions.assertEquals(Collections.singletonList("java"), result.get(0).getTagList());
    Assertions.assertEquals("b", result.get(1).getArticleId());
    Assertions.assertTrue(result.get(1).getTagList().isEmpty());
    Assertions.assertEquals(Arrays.asList("spring", "java"), result.get(2).getTagList());
  }

  @Test
  public void empty_batch_never_calls_the_service() {
    Assertions.assertTrue(adapter.tagsByArticleIds(Collections.emptyList()).isEmpty());
    verifyNoInteractions(client);
  }

  @Test
  public void all_tags_and_ids_by_tag_pass_through() {
    when(client.allTags()).thenReturn(Arrays.asList("java", "spring"));
    when(client.articleIdsByTag("java")).thenReturn(Collections.singletonList("a"));
    Assertions.assertEquals(Arrays.asList("java", "spring"), adapter.allTags());
    Assertions.assertEquals(Collections.singletonList("a"), adapter.articleIdsByTag("java"));
  }
}
