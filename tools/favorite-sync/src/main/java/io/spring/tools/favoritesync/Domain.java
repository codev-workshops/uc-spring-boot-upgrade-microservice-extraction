package io.spring.tools.favoritesync;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A synchronised domain: one or more {@link SyncTable}s that are backfilled and reconciled together
 * in a single run, in declaration order.
 */
public enum Domain {
  /** {@code article_favorites(article_id, user_id)} — key-only, no payload. */
  FAVORITE(
      "favorite",
      List.of(
          SyncTable.keyed(
              "article_favorites",
              List.of("article_id", "user_id"),
              List.of(),
              List.of("articleId", "userId")))),
  /** {@code comments(id, body, article_id, user_id, created_at, updated_at)} — keyed by id. */
  COMMENT(
      "comment",
      List.of(
          SyncTable.keyed(
              "comments",
              List.of("id"),
              List.of("body", "article_id", "user_id", "created_at", "updated_at"),
              List.of("id")))),
  /**
   * {@code tags(id, name)} keyed by {@code id} with {@code name} as the compared payload, then
   * {@code article_tags(article_id, tag_id)} keyed by the pair. Tags are copied first so a relation
   * never references a tag the target does not have yet.
   */
  TAG(
      "tag",
      List.of(
          SyncTable.keyed("tags", List.of("id"), List.of("name"), List.of("id")),
          SyncTable.unconstrained(
              "article_tags", List.of("article_id", "tag_id"), List.of("articleId", "tagId")))),
  /**
   * {@code articles(id, user_id, slug UNIQUE, title, description, body, created_at, updated_at)}
   * keyed by {@code id}; every other column is compared (timestamps as stored). {@code slug} is
   * UNIQUE on both sides, so a row whose slug is already held by a different id is reported as a
   * conflict. Tag tables are {@link #TAG}, run it first.
   */
  ARTICLE(
      "article",
      List.of(
          SyncTable.keyed(
              "articles",
              List.of("id"),
              List.of(
                  "user_id", "slug", "title", "description", "body", "created_at", "updated_at"),
              List.of("id"),
              List.of("slug")))),
  /**
   * {@code users(id, username UNIQUE, password, email UNIQUE, bio, image)} keyed by {@code id},
   * every other column compared ({@code password} is the stored hash, compared as stored and never
   * printed), then {@code follows(user_id, follow_id)} keyed by the pair with no constraint. Users
   * are copied first so a follow never references a user the target does not have yet. A username
   * or email already held by a different id is reported as a conflict.
   */
  USER(
      "user",
      List.of(
          SyncTable.keyed(
              "users",
              List.of("id"),
              List.of("username", "password", "email", "bio", "image"),
              List.of("id"),
              List.of("username", "email"),
              List.of("password")),
          SyncTable.unconstrained(
              "follows", List.of("user_id", "follow_id"), List.of("userId", "followId"))));

  public final String domainName;
  public final List<SyncTable> tables;

  Domain(String domainName, List<SyncTable> tables) {
    this.domainName = domainName;
    this.tables = tables;
  }

  public static Domain parse(String s) {
    for (Domain d : values()) {
      if (d.domainName.equals(s)) {
        return d;
      }
    }
    throw new SyncException(
        "--domain must be one of "
            + Arrays.stream(values()).map(d -> d.domainName).collect(Collectors.joining("|"))
            + ", got: "
            + s);
  }

  public List<String> tableNames() {
    return tables.stream().map(t -> t.table).collect(Collectors.toList());
  }
}
