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
              "article_tags", List.of("article_id", "tag_id"), List.of("articleId", "tagId"))));

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
