package io.spring.tools.favoritesync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Thin SQLite helpers for the {@code article_favorites} table (identical schema on both sides). */
public final class FavoriteDb {

  public static final String TABLE = "article_favorites";

  private FavoriteDb() {}

  public static Connection open(Path file, boolean readOnly) throws SQLException {
    if (!Files.isRegularFile(file)) {
      throw new SyncException("database file does not exist: " + file);
    }
    String url = "jdbc:sqlite:" + file.toAbsolutePath();
    if (readOnly) {
      url = "jdbc:sqlite:file:" + file.toAbsolutePath() + "?mode=ro";
    }
    Connection c = DriverManager.getConnection(url);
    requireTable(c, file);
    return c;
  }

  static void requireTable(Connection c, Path file) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("select 1 from sqlite_master where type = 'table' and name = ?")) {
      ps.setString(1, TABLE);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new SyncException(
              "table '"
                  + TABLE
                  + "' is missing in "
                  + file
                  + " (run the Flyway V1 migration of the owning application first)");
        }
      }
    }
  }

  /** Consistent point-in-time copy via SQLite's online backup API (WAL-safe). */
  public static void snapshot(Path source, Path target) throws SQLException {
    if (!Files.isRegularFile(source)) {
      throw new SyncException("database file does not exist: " + source);
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
        Statement st = c.createStatement()) {
      requireTable(c, source);
      st.executeUpdate("backup to '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
    }
  }

  public static long count(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + TABLE)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  public static long totalChanges(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select total_changes()")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** Streams natural keys in {@code (article_id, user_id)} order. Caller closes the statement. */
  public static PreparedStatement orderedKeys(Connection c) throws SQLException {
    PreparedStatement ps =
        c.prepareStatement(
            "select article_id, user_id from " + TABLE + " order by article_id, user_id");
    ps.setFetchSize(1000);
    return ps;
  }

  public static PreparedStatement insertOrIgnore(Connection c) throws SQLException {
    return c.prepareStatement(
        "insert or ignore into " + TABLE + " (article_id, user_id) values (?, ?)");
  }

  public static PreparedStatement deleteByKey(Connection c) throws SQLException {
    return c.prepareStatement("delete from " + TABLE + " where article_id = ? and user_id = ?");
  }
}
