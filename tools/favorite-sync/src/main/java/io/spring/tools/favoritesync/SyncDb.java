package io.spring.tools.favoritesync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Thin SQLite helpers for a {@link SyncTable} (identical schema on both sides). */
public final class SyncDb {

  private SyncDb() {}

  /** Opens {@code file} and verifies that every table of {@code domain} exists in it. */
  public static Connection open(Domain d, Path file, boolean readOnly) throws SQLException {
    if (!Files.isRegularFile(file)) {
      throw new SyncException("database file does not exist: " + file);
    }
    String url = "jdbc:sqlite:" + file.toAbsolutePath();
    if (readOnly) {
      url = "jdbc:sqlite:file:" + file.toAbsolutePath() + "?mode=ro";
    }
    Connection c = DriverManager.getConnection(url);
    requireTables(d, c, file);
    return c;
  }

  static void requireTables(Domain d, Connection c, Path file) throws SQLException {
    for (SyncTable t : d.tables) {
      requireTable(t, c, file);
    }
  }

  static void requireTable(SyncTable t, Connection c, Path file) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select 1 from sqlite_master where type = 'table' and name = ?")) {
      ps.setString(1, t.table);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new SyncException(
              "table '"
                  + t.table
                  + "' is missing in "
                  + file
                  + " (run the Flyway V1 migration of the owning application first)");
        }
      }
    }
  }

  /** Consistent point-in-time copy via SQLite's online backup API (WAL-safe). */
  public static void snapshot(Domain d, Path source, Path target) throws SQLException {
    if (!Files.isRegularFile(source)) {
      throw new SyncException("database file does not exist: " + source);
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
        Statement st = c.createStatement()) {
      requireTables(d, c, source);
      st.executeUpdate("backup to '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
    }
  }

  public static long count(SyncTable t, Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + t.table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  public static boolean existsByKey(SyncTable t, Connection c, Row row) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(t.existsByKey())) {
      row.bindKey(ps);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  public static long totalChanges(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select total_changes()")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** Streams full rows in natural-key order. Caller closes the statement. */
  public static PreparedStatement orderedRows(SyncTable t, Connection c) throws SQLException {
    PreparedStatement ps = c.prepareStatement(t.selectOrdered());
    ps.setFetchSize(1000);
    return ps;
  }

  public static PreparedStatement insertIfAbsent(SyncTable t, Connection c) throws SQLException {
    return c.prepareStatement(t.insertIfAbsent());
  }

  public static PreparedStatement deleteByKey(SyncTable t, Connection c) throws SQLException {
    return c.prepareStatement(t.deleteByKey());
  }

  public static PreparedStatement updateByKey(SyncTable t, Connection c) throws SQLException {
    return c.prepareStatement(t.updateByKey());
  }
}
