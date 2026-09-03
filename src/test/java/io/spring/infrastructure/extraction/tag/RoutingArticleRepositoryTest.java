package io.spring.infrastructure.extraction.tag;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.tag.TagCommandPort;
import io.spring.core.article.Article;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class RoutingArticleRepositoryTest {
  private final MyBatisArticleRepository monolith = mock(MyBatisArticleRepository.class);
  private final TagCommandPort tags = mock(TagCommandPort.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final RoutingArticleRepository repository =
      new RoutingArticleRepository(monolith, tags, properties);
  private final Article article =
      new Article("title", "desc", "body", Arrays.asList("java", "spring", "java"), "u1");

  @Test
  public void flag_off_delegates_everything_and_never_touches_the_tag_port() {
    when(monolith.findBySlug("title")).thenReturn(Optional.of(article));
    repository.save(article);
    verify(monolith).save(article);
    verify(monolith, never()).findById(article.getId());

    Assertions.assertSame(article, repository.findBySlug("title").get());
    repository.findById(article.getId());
    repository.remove(article);
    verify(monolith).findById(article.getId());
    verify(monolith).remove(article);
    verifyNoInteractions(tags);
  }

  @Test
  public void write_mode_monolith_does_not_mirror_even_when_enabled() {
    properties.getTag().setEnabled(true);
    repository.save(article);
    verify(monolith).save(article);
    verifyNoInteractions(tags);
  }

  @Test
  public void create_mirrors_the_deduplicated_tag_set_after_the_local_save() {
    properties.getTag().setEnabled(true);
    properties.getTag().setWrite(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());

    repository.save(article);

    InOrder order = inOrder(monolith, tags);
    order.verify(monolith).save(article);
    order.verify(tags).setTags(article.getId(), article.getTags());
    Assertions.assertEquals(2, article.getTags().size());
  }

  @Test
  public void update_never_writes_tags() {
    properties.getTag().setEnabled(true);
    properties.getTag().setWrite(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.of(article));

    repository.save(article);

    verify(monolith).save(article);
    verifyNoInteractions(tags);
  }

  @Test
  public void local_failure_propagates_and_skips_the_mirror() {
    properties.getTag().setEnabled(true);
    properties.getTag().setWrite(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(monolith).save(article);

    Assertions.assertThrows(IllegalStateException.class, () -> repository.save(article));
    verifyNoInteractions(tags);
  }
}
