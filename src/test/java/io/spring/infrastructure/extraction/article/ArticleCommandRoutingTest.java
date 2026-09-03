package io.spring.infrastructure.extraction.article;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.spring.application.article.ArticleCommandPort;
import io.spring.application.article.dto.ArticleRowDto;
import io.spring.core.article.Article;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import io.spring.infrastructure.extraction.ReadAfterWriteMarker;
import io.spring.infrastructure.extraction.tag.ArticleServiceException;
import io.spring.infrastructure.extraction.tag.RoutingArticleRepository;
import io.spring.infrastructure.repository.MyBatisArticleRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Write side of the Article seam: {@link LocalArticleCommand} (Phase 3 preservation), {@link
 * DualWriteArticleCommand}, {@link RoutingArticleCommandPort} and the {@code @Primary} {@link
 * RoutedArticleRepository}.
 */
public class ArticleCommandRoutingTest {
  private final MyBatisArticleRepository monolith = mock(MyBatisArticleRepository.class);
  private final RoutingArticleRepository phase3 = mock(RoutingArticleRepository.class);
  private final ArticleDomainServiceClient client = mock(ArticleDomainServiceClient.class);
  private final ExtractionProperties properties = new ExtractionProperties();
  private final ReadAfterWriteMarker marker = new ReadAfterWriteMarker();

  private final LocalArticleCommand local = new LocalArticleCommand(monolith, phase3, properties);
  private final RemoteArticleCommand remote = new RemoteArticleCommand(client);
  private final DualWriteArticleCommand dualWrite = new DualWriteArticleCommand(local, remote);
  private final RoutingArticleCommandPort commands =
      new RoutingArticleCommandPort(local, dualWrite, remote, properties, marker);
  private final RoutedArticleRepository repository =
      new RoutedArticleRepository(monolith, commands, client, properties);

  private final Article article =
      new Article("title", "desc", "body", Arrays.asList("java", "spring", "java"), "u1");

  @AfterEach
  public void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  public void flag_off_creates_through_the_phase3_tag_seam_and_never_touches_the_service() {
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());
    repository.save(article);
    verify(phase3).save(article);
    verify(monolith, never()).save(article);

    when(monolith.findById(article.getId())).thenReturn(Optional.of(article));
    when(monolith.findBySlug("title")).thenReturn(Optional.of(article));
    repository.save(article);
    verify(monolith).save(article);
    Assertions.assertSame(article, repository.findBySlug("title").get());
    Assertions.assertSame(article, repository.findById(article.getId()).get());
    repository.remove(article);
    verify(monolith).remove(article);
    verifyNoInteractions(client);
  }

  @Test
  public void write_mode_monolith_is_local_even_when_enabled() {
    properties.getArticle().setEnabled(true);
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());
    repository.save(article);
    verify(phase3).save(article);
    verifyNoInteractions(client);
  }

  @Test
  public void dual_write_creates_locally_first_then_posts_the_row_with_tags_and_skips_phase3() {
    enable(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());
    when(client.create(article)).thenReturn(new ArticleRowDto());

    repository.save(article);

    InOrder order = inOrder(monolith, client);
    order.verify(monolith).save(article);
    order.verify(client).create(article);
    verifyNoInteractions(phase3);
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void dual_write_updates_and_deletes_locally_then_remotely() {
    enable(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.of(article));

    repository.save(article);
    repository.remove(article);

    InOrder order = inOrder(monolith, client);
    order.verify(monolith).save(article);
    order.verify(client).update(article);
    order.verify(monolith).remove(article);
    order.verify(client).delete(article.getId());
    verify(client, never()).create(article);
  }

  @Test
  public void dual_write_remote_failure_is_recorded_and_never_rolls_back_the_local_write() {
    enable(WriteMode.DUAL_WRITE);
    when(monolith.findById(article.getId())).thenReturn(Optional.empty());
    doThrow(new ArticleServiceException("down", null)).when(client).create(article);

    repository.save(article);

    verify(monolith).save(article);
    Assertions.assertEquals(1, dualWrite.pendingMirrorOperations().size());
    PendingArticleMirrorOperation pending = dualWrite.pendingMirrorOperations().get(0);
    Assertions.assertEquals(PendingArticleMirrorOperation.Kind.CREATE, pending.getKind());
    Assertions.assertEquals(article.getId(), pending.getArticleId());
    Assertions.assertEquals("down", pending.getError());
    dualWrite.clearPending();
    Assertions.assertTrue(dualWrite.pendingMirrorOperations().isEmpty());
  }

  @Test
  public void lookups_stay_local_while_the_monolith_is_authoritative() {
    enable(WriteMode.DUAL_WRITE);
    when(monolith.findBySlug("title")).thenReturn(Optional.of(article));
    Assertions.assertSame(article, repository.findBySlug("title").get());
    verify(client, never()).findBySlug("title");
  }

  @Test
  public void extracted_writes_go_remote_only_and_lookups_move_to_the_service() {
    enable(WriteMode.EXTRACTED);
    ArticleRowDto row =
        new ArticleRowDto(
            article.getId(),
            "title",
            "title",
            "desc",
            "body",
            "u1",
            "2024-01-03T00:00:00.000Z",
            "2024-01-03T00:00:00.000Z",
            Arrays.asList("java", "spring"));
    when(client.findById(article.getId())).thenReturn(Optional.empty(), Optional.of(row));
    when(client.findBySlug("title")).thenReturn(Optional.of(row));

    repository.save(article);
    verify(client).create(article);

    Article found = repository.findBySlug("title").get();
    Assertions.assertEquals(article.getId(), found.getId());
    Assertions.assertEquals("u1", found.getUserId());
    Assertions.assertEquals("title", found.getSlug());
    Assertions.assertEquals(2, found.getTags().size());
    Assertions.assertEquals(2024, found.getCreatedAt().getYear());

    repository.save(found);
    verify(client).update(found);
    repository.remove(found);
    verify(client).delete(article.getId());
    verify(monolith, never()).save(article);
    verify(monolith, never()).remove(article);
    verifyNoInteractions(phase3);
    Assertions.assertFalse(repository.findById("missing").isPresent());
  }

  @Test
  public void extracted_write_failures_surface_to_the_caller() {
    enable(WriteMode.EXTRACTED);
    when(client.findById(article.getId())).thenReturn(Optional.empty());
    doThrow(new ArticleServiceException("422", null)).when(client).create(article);
    Assertions.assertThrows(ArticleServiceException.class, () -> repository.save(article));
    verifyNoInteractions(monolith);
  }

  @Test
  public void writes_mark_the_domain_for_read_after_write() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    Assertions.assertFalse(marker.writtenInThisRequest(RoutingArticleQueryPort.DOMAIN));
    commands.update(article);
    Assertions.assertTrue(marker.writtenInThisRequest(RoutingArticleQueryPort.DOMAIN));
  }

  @Test
  public void select_follows_the_write_mode() {
    Assertions.assertSame(local, commands.select());
    properties.getArticle().setWrite(WriteMode.EXTRACTED);
    Assertions.assertSame(local, commands.select());
    enable(WriteMode.DUAL_WRITE);
    Assertions.assertSame(dualWrite, commands.select());
    enable(WriteMode.EXTRACTED);
    Assertions.assertSame(remote, commands.select());
    enable(WriteMode.MONOLITH);
    Assertions.assertSame(local, commands.select());
    ArticleCommandPort selected = commands.select();
    Assertions.assertEquals(Collections.singletonList(local), Collections.singletonList(selected));
  }

  private void enable(WriteMode mode) {
    properties.getArticle().setEnabled(true);
    properties.getArticle().setWrite(mode);
  }
}
