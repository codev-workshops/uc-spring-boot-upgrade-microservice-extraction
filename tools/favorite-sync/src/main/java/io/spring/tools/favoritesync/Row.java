package io.spring.tools.favoritesync;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One row of a {@link Domain} table: natural key (always text) plus the raw payload values exactly
 * as SQLite stores them ({@code Long}, {@code Double}, {@code String} or {@code byte[]}), so a copy
 * round-trips timestamps and text without reformatting. Ordering and equality follow the key;
 * {@link #samePayload} compares the payload.
 */
public final class Row implements Comparable<Row> {
  public final String[] key;
  public final Object[] payload;

  public Row(String[] key, Object[] payload) {
    this.key = Objects.requireNonNull(key);
    this.payload = Objects.requireNonNull(payload);
    for (String k : key) {
      Objects.requireNonNull(k, "natural key column is null");
    }
  }

  static Row read(ResultSet rs, Domain d) throws SQLException {
    String[] key = new String[d.keyColumns.size()];
    for (int i = 0; i < key.length; i++) {
      key[i] = rs.getString(i + 1);
    }
    Object[] payload = new Object[d.payloadColumns.size()];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = rs.getObject(key.length + i + 1);
    }
    return new Row(key, payload);
  }

  /** Binds key then payload, matching the column order of {@link Domain#insertOrIgnore()}. */
  void bindAll(PreparedStatement ps) throws SQLException {
    int i = 1;
    for (String k : key) {
      ps.setString(i++, k);
    }
    for (Object p : payload) {
      ps.setObject(i++, p);
    }
  }

  /** Binds payload then key, matching {@link Domain#updateByKey()}. */
  void bindPayloadThenKey(PreparedStatement ps) throws SQLException {
    int i = 1;
    for (Object p : payload) {
      ps.setObject(i++, p);
    }
    for (String k : key) {
      ps.setString(i++, k);
    }
  }

  void bindKey(PreparedStatement ps) throws SQLException {
    for (int i = 0; i < key.length; i++) {
      ps.setString(i + 1, key[i]);
    }
  }

  public boolean samePayload(Row o) {
    return Arrays.deepEquals(payload, o.payload);
  }

  /** Names of payload columns whose values differ from {@code o}. */
  public List<String> differingColumns(Row o, Domain d) {
    List<String> cols = new ArrayList<>();
    for (int i = 0; i < payload.length; i++) {
      if (!Objects.deepEquals(payload[i], o.payload[i])) {
        cols.add(d.payloadColumns.get(i));
      }
    }
    return cols;
  }

  /** Stable, type-tagged text used for the ordered-stream checksum. */
  byte[] digestBytes() {
    StringBuilder sb = new StringBuilder(String.join("|", key));
    for (Object p : payload) {
      sb.append('|').append(canonical(p));
    }
    sb.append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String canonical(Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof byte[]) {
      StringBuilder sb = new StringBuilder("blob:");
      for (byte b : (byte[]) v) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    }
    if (v instanceof Number) {
      return "num:" + v;
    }
    return "text:" + v;
  }

  @Override
  public int compareTo(Row o) {
    for (int i = 0; i < key.length; i++) {
      int c = key[i].compareTo(o.key[i]);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Row && Arrays.equals(key, ((Row) o).key);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(key);
  }

  @Override
  public String toString() {
    return String.join("|", key);
  }
}
