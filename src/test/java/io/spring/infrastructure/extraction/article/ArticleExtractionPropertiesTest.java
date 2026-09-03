package io.spring.infrastructure.extraction.article;

import io.spring.application.article.dto.ArticleRowDto;
import io.spring.application.data.ArticleRow;
import io.spring.infrastructure.extraction.ExtractionProperties;
import io.spring.infrastructure.extraction.ExtractionProperties.DomainRoute;
import io.spring.infrastructure.extraction.ExtractionProperties.Fallback;
import io.spring.infrastructure.extraction.ExtractionProperties.ReadMode;
import io.spring.infrastructure.extraction.ExtractionProperties.WriteMode;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class ArticleExtractionPropertiesTest {
  @Test
  public void article_route_defaults_to_off_monolith_and_port_8083() {
    DomainRoute article = new ExtractionProperties().getArticle();
    Assertions.assertFalse(article.isEnabled());
    Assertions.assertEquals(ReadMode.MONOLITH, article.getRead());
    Assertions.assertEquals(WriteMode.MONOLITH, article.getWrite());
    Assertions.assertEquals(Fallback.MONOLITH, article.getFallback());
    Assertions.assertEquals(URI.create("http://localhost:8083"), article.getBaseUrl());
    Assertions.assertEquals(Duration.ofMillis(500), article.getConnectTimeout());
    Assertions.assertEquals(Duration.ofMillis(1500), article.getReadTimeout());
    Assertions.assertFalse(article.readsRemote());
    Assertions.assertFalse(article.shadows());
    Assertions.assertFalse(article.writesRemote());
    Assertions.assertTrue(article.monolithAuthoritative());
  }

  @Test
  public void article_route_binds_independently_of_the_other_domains() {
    Map<String, Object> source = new HashMap<>();
    source.put("extraction.article.enabled", "true");
    source.put("extraction.article.read", "extracted");
    source.put("extraction.article.write", "extracted");
    source.put("extraction.article.fallback", "fail");
    source.put("extraction.article.base-url", "http://article:9000");
    source.put("extraction.article.connect-timeout", "1s");
    ExtractionProperties properties =
        new Binder(new MapConfigurationPropertySource(source))
            .bind("extraction", ExtractionProperties.class)
            .get();

    DomainRoute article = properties.getArticle();
    Assertions.assertTrue(article.readsRemote());
    Assertions.assertTrue(article.writesRemote());
    Assertions.assertFalse(article.writesLocal());
    Assertions.assertFalse(article.monolithAuthoritative());
    Assertions.assertEquals(Fallback.FAIL, article.getFallback());
    Assertions.assertEquals("http://article:9000", article.getBaseUrl().toString());
    Assertions.assertEquals(Duration.ofSeconds(1), article.getConnectTimeout());
    Assertions.assertFalse(properties.getTag().isEnabled());
    Assertions.assertEquals("http://localhost:8083", properties.getTag().getBaseUrl().toString());
    Assertions.assertFalse(properties.getFavorite().isEnabled());
    Assertions.assertFalse(properties.getComment().isEnabled());
  }

  @Test
  public void remote_rows_are_mapped_with_iso_timestamps_and_tag_lists() {
    ArticleRowDto dto =
        new ArticleRowDto(
            "a1",
            "slug",
            "title",
            "d",
            "b",
            "u1",
            "2024-01-03T10:15:30.000Z",
            "2024-01-04T00:00:00.000Z",
            Arrays.asList("java", "spring"));

    ArticleRow row = RemoteArticleQueryAdapter.toRow(dto);

    Assertions.assertEquals("a1", row.getId());
    Assertions.assertEquals("u1", row.getUserId());
    Assertions.assertEquals(
        "2024-01-03T10:15:30.000Z", row.getCreatedAt().withZone(DateTimeZone.UTC).toString());
    Assertions.assertEquals(4, row.getUpdatedAt().withZone(DateTimeZone.UTC).getDayOfMonth());
    Assertions.assertEquals(Arrays.asList("java", "spring"), row.getTagList());
    Assertions.assertNull(RemoteArticleQueryAdapter.parse(null));
    Assertions.assertTrue(
        RemoteArticleQueryAdapter.toRow(new ArticleRowDto()).getTagList().isEmpty());
  }
}
