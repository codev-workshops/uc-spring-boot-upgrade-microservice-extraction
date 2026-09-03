package io.spring.tools.favoritesync;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A synchronised table: its natural key, its mutable payload (compared for the {@code diverged}
 * bucket) and the SQL the tool needs. Both databases must hold the table with identical DDL.
 */
public enum Domain {
  /** {@code article_favorites(article_id, user_id)} — key-only, no payload. */
  FAVORITE(
      "favorite",
      "article_favorites",
      List.of("article_id", "user_id"),
      List.of(),
      List.of("articleId", "userId")),
  /** {@code comments(id, body, article_id, user_id, created_at, updated_at)} — keyed by id. */
  COMMENT(
      "comment",
      "comments",
      List.of("id"),
      List.of("body", "article_id", "user_id", "created_at", "updated_at"),
      List.of("id"));

  public final String domainName;
  public final String table;
  public final List<String> keyColumns;
  public final List<String> payloadColumns;
  /** camelCase names used for the key fields in the JSON report. */
  public final List<String> keyJsonNames;

  Domain(
      String domainName,
      String table,
      List<String> keyColumns,
      List<String> payloadColumns,
      List<String> keyJsonNames) {
    this.domainName = domainName;
    this.table = table;
    this.keyColumns = keyColumns;
    this.payloadColumns = payloadColumns;
    this.keyJsonNames = keyJsonNames;
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

  public List<String> allColumns() {
    return Stream.concat(keyColumns.stream(), payloadColumns.stream()).collect(Collectors.toList());
  }

  public boolean hasPayload() {
    return !payloadColumns.isEmpty();
  }

  private String columnList() {
    return String.join(", ", allColumns());
  }

  private String keyList() {
    return String.join(", ", keyColumns);
  }

  private String keyTuple() {
    return keyColumns.size() == 1 ? keyList() : "(" + keyList() + ")";
  }

  private static String marks(int n) {
    String m = String.join(", ", java.util.Collections.nCopies(n, "?"));
    return n == 1 ? m : "(" + m + ")";
  }

  /** Full rows in natural-key order (streamed by the reconcile merge-join). */
  String selectOrdered() {
    return "select " + columnList() + " from " + table + " order by " + keyList();
  }

  /** Keyset page of full rows: {@code after == false} for the first page. */
  String selectChunk(boolean after) {
    return "select "
        + columnList()
        + " from "
        + table
        + (after ? " where " + keyTuple() + " > " + marks(keyColumns.size()) : "")
        + " order by "
        + keyList()
        + " limit ?";
  }

  String insertOrIgnore() {
    int n = allColumns().size();
    return "insert or ignore into "
        + table
        + " ("
        + columnList()
        + ") values ("
        + String.join(", ", java.util.Collections.nCopies(n, "?"))
        + ")";
  }

  private String keyWhere() {
    return keyColumns.stream().map(c -> c + " = ?").collect(Collectors.joining(" and "));
  }

  String deleteByKey() {
    return "delete from " + table + " where " + keyWhere();
  }

  /** Overwrites the payload of an existing row; only meaningful when {@link #hasPayload()}. */
  String updateByKey() {
    return "update "
        + table
        + " set "
        + payloadColumns.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
        + " where "
        + keyWhere();
  }
}
