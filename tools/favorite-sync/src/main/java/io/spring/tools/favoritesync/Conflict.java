package io.spring.tools.favoritesync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A row that cannot be written to the other side because a UNIQUE payload column ({@code
 * articles.slug}) already holds the same value under a different key there. {@code INSERT OR
 * IGNORE} would drop such a row silently and an {@code UPDATE} would fail, so conflicts are
 * detected up front and reported instead. A table may have several unique columns ({@code
 * users.username} and {@code users.email}); one row then yields one conflict per clashing column,
 * possibly held by different keys. Values of sensitive columns are rendered as {@link #REDACTED}.
 */
public final class Conflict {
  public static final String REDACTED = "<redacted>";

  public final Row row;
  public final String column;
  public final String value;
  /** Key (single column) of the row on the other side that holds {@link #value}. */
  public final String conflictingKey;

  Conflict(Row row, String column, String value, String conflictingKey) {
    this.row = row;
    this.column = column;
    this.value = value;
    this.conflictingKey = conflictingKey;
  }

  /**
   * Checks every unique column of {@code row} against {@code other}; returns one conflict per
   * clashing column, empty when the row could be written there.
   */
  static List<Conflict> find(SyncTable table, Connection other, Row row) throws SQLException {
    List<Conflict> found = new ArrayList<>();
    for (String column : table.uniqueColumns) {
      Object v = row.payload[table.payloadIndex(column)];
      if (v == null) {
        continue;
      }
      try (PreparedStatement ps = other.prepareStatement(table.selectKeyByColumn(column))) {
        ps.setObject(1, v);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String holder = rs.getString(1);
            if (!holder.equals(row.key[0])) {
              String shown = table.isSensitive(column) ? REDACTED : String.valueOf(v);
              found.add(new Conflict(row, column, shown, holder));
            }
          }
        }
      }
    }
    return found;
  }

  /** Conflicts of {@code rows} against {@code other}, in row order. */
  static void collect(SyncTable table, Connection other, List<Row> rows, List<Conflict> into)
      throws SQLException {
    if (!table.hasUniqueColumns()) {
      return;
    }
    for (Row r : rows) {
      into.addAll(find(table, other, r));
    }
  }

  @Override
  public String toString() {
    return row + " " + column + "=" + value + " held by " + conflictingKey;
  }
}
