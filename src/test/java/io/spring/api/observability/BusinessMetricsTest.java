package io.spring.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BusinessMetricsTest {
  private MeterRegistry registry;
  private BusinessMetrics metrics;

  @BeforeEach
  public void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new BusinessMetrics(registry);
  }

  private double count(String name, String tagKey, String tagValue) {
    return registry.get(name).tag(tagKey, tagValue).counter().count();
  }

  @Test
  public void should_count_article_operations() {
    metrics.articleCreated();
    metrics.articleCreated();
    metrics.articleUpdated();
    metrics.articleFavorited();
    metrics.articleUnfavorited();

    assertEquals(2, count(BusinessMetrics.ARTICLES_CREATED, "operation", "create"));
    assertEquals(1, count(BusinessMetrics.ARTICLES_UPDATED, "operation", "update"));
    assertEquals(1, count(BusinessMetrics.ARTICLES_FAVORITED, "operation", "favorite"));
    assertEquals(1, count(BusinessMetrics.ARTICLES_FAVORITED, "operation", "unfavorite"));
  }

  @Test
  public void should_count_user_operations() {
    metrics.userRegistered();
    metrics.loginSucceeded();
    metrics.loginFailed();
    metrics.loginFailed();
    metrics.userFollowed();
    metrics.userUnfollowed();
    metrics.commentCreated();

    assertEquals(1, count(BusinessMetrics.USERS_REGISTERED, "operation", "register"));
    assertEquals(1, count(BusinessMetrics.USERS_LOGIN, "result", "success"));
    assertEquals(2, count(BusinessMetrics.USERS_LOGIN, "result", "failure"));
    assertEquals(1, count(BusinessMetrics.USERS_FOLLOW, "operation", "follow"));
    assertEquals(1, count(BusinessMetrics.USERS_FOLLOW, "operation", "unfollow"));
    assertEquals(1, count(BusinessMetrics.COMMENTS_CREATED, "operation", "create"));
  }
}
