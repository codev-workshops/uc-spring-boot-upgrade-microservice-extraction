package io.spring.tools.favoritesync;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One synchronised table: its natural key, its mutable payload (compared for the {@code diverged}
 * bucket) and the SQL the tool needs. Both databases must hold the table with identical DDL.
 *
 * <p>{@link #uniqueKey} says whether SQLite enforces the natural key (primary key or unique index).
 * When it does not — {@code article_tags(article_id, tag_id)} has no constraint at all — {@code
 * INSERT OR IGNORE} would duplicate rows, so inserts are guarded by a {@code NOT EXISTS} check
 * instead and duplicate rows are reported rather than multiplied.
 *
 * <p>{@link #uniqueColumns} lists payload columns that SQLite also keeps unique ({@code
 * articles.slug}). A row whose key is absent on the other side but whose unique value is held there
 * by a <em>different</em> key can never be inserted; it is detected and reported as a conflict
 * instead of being silently swallowed by {@code INSERT OR IGNORE}.
 */
public final class SyncTable {

  public final String table;
  public final List<String> keyColumns;
  public final List<String> payloadColumns;
  /** camelCase names used for the key fields in the JSON report. */
  public final List<String> keyJsonNames;

  public final boolean uniqueKey;
  /** Payload columns with their own UNIQUE constraint (subset of {@link #payloadColumns}). */
  public final List<String> uniqueColumns;

  private SyncTable(
      String table,
      List<String> keyColumns,
      List<String> payloadColumns,
      List<String> keyJsonNames,
      boolean uniqueKey,
      List<String> uniqueColumns) {
    this.table = table;
    this.keyColumns = keyColumns;
    this.payloadColumns = payloadColumns;
    this.keyJsonNames = keyJsonNames;
    this.uniqueKey = uniqueKey;
    this.uniqueColumns = uniqueColumns;
    for (String u : uniqueColumns) {
      if (!payloadColumns.contains(u)) {
        throw new IllegalArgumentException("unique column " + u + " is not a payload column");
      }
    }
    if (!uniqueColumns.isEmpty() && keyColumns.size() != 1) {
      throw new IllegalArgumentException("unique columns need a single-column key: " + table);
    }
  }

  /** A table whose natural key is enforced by SQLite. */
  public static SyncTable keyed(
      String table,
      List<String> keyColumns,
      List<String> payloadColumns,
      List<String> keyJsonNames) {
    return new SyncTable(table, keyColumns, payloadColumns, keyJsonNames, true, List.of());
  }

  /** A keyed table whose payload also carries UNIQUE columns (e.g. {@code articles.slug}). */
  public static SyncTable keyed(
      String table,
      List<String> keyColumns,
      List<String> payloadColumns,
      List<String> keyJsonNames,
      List<String> uniqueColumns) {
    return new SyncTable(table, keyColumns, payloadColumns, keyJsonNames, true, uniqueColumns);
  }

  /** A table with no unique constraint on its natural key; inserts check for the key first. */
  public static SyncTable unconstrained(
      String table, List<String> keyColumns, List<String> keyJsonNames) {
    return new SyncTable(table, keyColumns, List.of(), keyJsonNames, false, List.of());
  }

  public boolean hasUniqueColumns() {
    return !uniqueColumns.isEmpty();
  }

  /** Index of {@code column} in {@link #payloadColumns}. */
  public int payloadIndex(String column) {
    int i = payloadColumns.indexOf(column);
    if (i < 0) {
      throw new IllegalArgumentException(column + " is not a payload column of " + table);
    }
    return i;
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
    String m = String.join(", ", Collections.nCopies(n, "?"));
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

  /**
   * Insert that leaves an already-present key alone: {@code INSERT OR IGNORE} when SQLite enforces
   * the key, otherwise an explicit "only if the key is absent" insert. Parameters are the row's
   * columns, followed by the key again when {@link #uniqueKey} is false.
   */
  String insertIfAbsent() {
    if (uniqueKey) {
      return "insert or ignore into "
          + table
          + " ("
          + columnList()
          + ") values ("
          + String.join(", ", Collections.nCopies(allColumns().size(), "?"))
          + ")";
    }
    return "insert into "
        + table
        + " ("
        + columnList()
        + ") select "
        + String.join(", ", Collections.nCopies(allColumns().size(), "?"))
        + " where not exists (select 1 from "
        + table
        + " where "
        + keyWhere()
        + ")";
  }

  /** The key of the row holding a given value in a unique column, if any. */
  String selectKeyByColumn(String column) {
    return "select " + keyList() + " from " + table + " where " + column + " = ?";
  }

  String existsByKey() {
    return "select 1 from " + table + " where " + keyWhere();
  }

  private String keyWhere() {
    return keyColumns.stream().map(c -> c + " = ?").collect(Collectors.joining(" and "));
  }

  /** Deletes every row with this key (all copies, for an unconstrained table). */
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

  @Override
  public String toString() {
    return table;
  }
}
