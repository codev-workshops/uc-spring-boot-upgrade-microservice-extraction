package io.spring.graphql;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.spring.application.TagsQueryService;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TagDatafetcherTest {

  @Test
  public void should_return_all_tags() {
    TagsQueryService tagsQueryService = mock(TagsQueryService.class);
    when(tagsQueryService.allTags()).thenReturn(Arrays.asList("java", "spring"));

    Assertions.assertEquals(
        Arrays.asList("java", "spring"), new TagDatafetcher(tagsQueryService).getTags());
  }
}
