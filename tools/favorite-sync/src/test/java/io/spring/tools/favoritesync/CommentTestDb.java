package io.spring.tools.favoritesync;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Builds SQLite fixtures with the exact {@code comments} DDL of V1__create_tables.sql. Rows can be
 * written the way the monolith writes them ({@code setTimestamp}, stored as INTEGER millis) or the
 * way the seed script writes them ({@code datetime('now')}, stored as TEXT).
 */
final class CommentTestDb {

  static final String DDL =
      "create table comments ("
          + " id varchar(255) primary key,"
          + " body text,"
          + " article_id varchar(255),"
          + " user_id varchar(255),"
          + " created_at TIMESTAMP NOT NULL,"
          + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";

  private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

  private CommentTestDb() {}

  static Path create(Path dir, String name) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      st.executeUpdate(DDL);
    }
    return file;
  }

  /** Inserts like {@code CommentMapper.xml} + {@code DateTimeHandler}: INTEGER millis. */
  static void insertMonolithStyle(
      Path file, String id, String body, String articleId, String userId, long createdMillis)
      throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "insert into comments (id, body, article_id, user_id, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, body);
      ps.setString(3, articleId);
      ps.setString(4, userId);
      ps.setTimestamp(5, new Timestamp(createdMillis), UTC);
      ps.setTimestamp(6, new Timestamp(createdMillis), UTC);
      ps.executeUpdate();
    }
  }

  /** Inserts like {@code V2__seed_data.sql}: TEXT {@code datetime(...)}. */
  static void insertSeedStyle(
      Path file, String id, String body, String articleId, String userId, String dateExpr)
      throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "insert into comments (id, body, article_id, user_id, created_at, updated_at)"
                    + " values (?, ?, ?, ?, "
                    + dateExpr
                    + ", "
                    + dateExpr
                    + ")")) {
      ps.setString(1, id);
      ps.setString(2, body);
      ps.setString(3, articleId);
      ps.setString(4, userId);
      ps.executeUpdate();
    }
  }

  static void insert(Path file, String id) throws SQLException {
    insertMonolithStyle(file, id, "body of " + id, "article-1", "user-1", 1_700_000_000_000L);
  }

  static void insertMany(Path file, int n) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert or ignore into comments (id, body, article_id, user_id, created_at,"
                  + " updated_at) values (?, ?, ?, ?, ?, ?)")) {
        for (int i = 0; i < n; i++) {
          ps.setString(1, String.format("comment-%06d", i));
          ps.setString(2, "body\n\"quoted\" 'text' #" + i);
          ps.setString(3, "article-" + (i % 37));
          ps.setString(4, "user-" + (i % 11));
          ps.setTimestamp(5, new Timestamp(1_700_000_000_000L + i * 1000L), UTC);
          ps.setTimestamp(6, new Timestamp(1_700_000_000_000L + i * 1000L), UTC);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  static void updateBody(Path file, String id, String body) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement("update comments set body = ? where id = ?")) {
      ps.setString(1, body);
      ps.setString(2, id);
      ps.executeUpdate();
    }
  }

  static void delete(Path file, String id) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement("delete from comments where id = ?")) {
      ps.setString(1, id);
      ps.executeUpdate();
    }
  }

  /** id -> "typeof(created_at):created_at|typeof(updated_at):updated_at|body|article|user". */
  static TreeMap<String, String> rows(Path file) throws SQLException {
    TreeMap<String, String> map = new TreeMap<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select id, body, article_id, user_id, typeof(created_at), created_at,"
                    + " typeof(updated_at), updated_at from comments")) {
      while (rs.next()) {
        map.put(
            rs.getString(1),
            rs.getString(5)
                + ":"
                + rs.getString(6)
                + "|"
                + rs.getString(7)
                + ":"
                + rs.getString(8)
                + "|"
                + rs.getString(2)
                + "|"
                + rs.getString(3)
                + "|"
                + rs.getString(4));
      }
    }
    return map;
  }

  static List<String> ids(Path file) throws SQLException {
    return new ArrayList<>(rows(file).keySet());
  }

  static long count(Path file) throws SQLException {
    return rows(file).size();
  }
}
