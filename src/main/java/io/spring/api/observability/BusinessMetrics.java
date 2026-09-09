package io.spring.api.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetrics {
  public static final String ARTICLES_CREATED = "articles.created";
  public static final String ARTICLES_UPDATED = "articles.updated";
  public static final String ARTICLES_FAVORITED = "articles.favorited";
  public static final String COMMENTS_CREATED = "comments.created";
  public static final String USERS_REGISTERED = "users.registered";
  public static final String USERS_LOGIN = "users.login";
  public static final String USERS_FOLLOW = "users.follow";

  private final Counter articlesCreated;
  private final Counter articlesUpdated;
  private final Counter articlesFavorited;
  private final Counter articlesUnfavorited;
  private final Counter commentsCreated;
  private final Counter usersRegistered;
  private final Counter loginSuccess;
  private final Counter loginFailure;
  private final Counter usersFollowed;
  private final Counter usersUnfollowed;

  public BusinessMetrics(MeterRegistry registry) {
    articlesCreated = counter(registry, ARTICLES_CREATED, "operation", "create");
    articlesUpdated = counter(registry, ARTICLES_UPDATED, "operation", "update");
    articlesFavorited = counter(registry, ARTICLES_FAVORITED, "operation", "favorite");
    articlesUnfavorited = counter(registry, ARTICLES_FAVORITED, "operation", "unfavorite");
    commentsCreated = counter(registry, COMMENTS_CREATED, "operation", "create");
    usersRegistered = counter(registry, USERS_REGISTERED, "operation", "register");
    loginSuccess = counter(registry, USERS_LOGIN, "result", "success");
    loginFailure = counter(registry, USERS_LOGIN, "result", "failure");
    usersFollowed = counter(registry, USERS_FOLLOW, "operation", "follow");
    usersUnfollowed = counter(registry, USERS_FOLLOW, "operation", "unfollow");
  }

  private static Counter counter(MeterRegistry registry, String name, String tagKey, String tag) {
    return Counter.builder(name).tags(tagKey, tag).register(registry);
  }

  public void articleCreated() {
    articlesCreated.increment();
  }

  public void articleUpdated() {
    articlesUpdated.increment();
  }

  public void articleFavorited() {
    articlesFavorited.increment();
  }

  public void articleUnfavorited() {
    articlesUnfavorited.increment();
  }

  public void commentCreated() {
    commentsCreated.increment();
  }

  public void userRegistered() {
    usersRegistered.increment();
  }

  public void loginSucceeded() {
    loginSuccess.increment();
  }

  public void loginFailed() {
    loginFailure.increment();
  }

  public void userFollowed() {
    usersFollowed.increment();
  }

  public void userUnfollowed() {
    usersUnfollowed.increment();
  }
}
