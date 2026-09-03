package io.spring.infrastructure.extraction.tag;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Tag;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DualWriteTagCommandTest {
  private final TagCommandPort remote = mock(TagCommandPort.class);
  private final DualWriteTagCommand command = new DualWriteTagCommand(remote);
  private final List<Tag> tags = Arrays.asList(new Tag("java"), new Tag("spring"));

  @Test
  public void mirrors_the_committed_tag_set() {
    command.setTags("a", tags);
    verify(remote).setTags("a", tags);
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void remote_failures_are_recorded_and_never_surfaced() {
    doThrow(new ArticleServiceException("down", null)).when(remote).setTags("a", tags);

    command.setTags("a", tags);

    Assertions.assertEquals(1, command.pendingMirrorOperations().size());
    PendingTagMirrorOperation pending = command.pendingMirrorOperations().get(0);
    Assertions.assertEquals("a", pending.getArticleId());
    Assertions.assertEquals(Arrays.asList("java", "spring"), pending.getTagNames());
    Assertions.assertEquals("down", pending.getError());
    command.clearPending();
    Assertions.assertTrue(command.pendingMirrorOperations().isEmpty());
  }
}
