package io.spring.infrastructure.extraction.comment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.comment.CommentCommandPort;
import io.spring.application.comment.dto.CommentRowDto;
import io.spring.core.comment.Comment;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.repository.MyBatisCommentRepository;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RoutingCommentRepositoryTest {
  private final MyBatisCommentRepository monolith = mock(MyBatisCommentRepository.class);
  private final CommentCommandPort commands = mock(CommentCommandPort.class);
  private final CommentServiceClient client = mock(CommentServiceClient.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final RoutingCommentRepository repository =
      new RoutingCommentRepository(monolith, commands, client, properties);
  private final Comment comment = new Comment("body", "u", "a");

  @Test
  public void writes_go_through_the_command_port() {
    repository.save(comment);
    repository.remove(comment);
    verify(commands).create(comment);
    verify(commands).delete("a", comment.getId());
    verifyNoInteractions(monolith, client);
  }

  @Test
  public void lookups_stay_on_the_monolith_while_it_is_authoritative() {
    when(monolith.findById("a", "c")).thenReturn(Optional.of(comment));
    properties.getComment().setEnabled(true);
    properties.getComment().setWrite(WriteMode.DUAL_WRITE);

    Assertions.assertSame(comment, repository.findById("a", "c").get());
    verifyNoInteractions(client);
  }

  @Test
  public void lookups_move_to_the_service_once_it_owns_writes() {
    properties.getComment().setEnabled(true);
    properties.getComment().setWrite(WriteMode.EXTRACTED);
    when(client.findById("c"))
        .thenReturn(
            Optional.of(
                new CommentRowDto("c", "body", "a", "u", "2024-01-01T00:00:00.000Z", null)));

    Comment found = repository.findById("a", "c").get();
    Assertions.assertEquals("c", found.getId());
    Assertions.assertEquals("u", found.getUserId());
    Assertions.assertEquals("a", found.getArticleId());
    Assertions.assertEquals(1704067200000L, found.getCreatedAt().getMillis());
    Assertions.assertFalse(repository.findById("other-article", "c").isPresent());
    verifyNoInteractions(monolith);
  }
}
