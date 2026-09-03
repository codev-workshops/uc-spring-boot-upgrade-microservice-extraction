package io.spring.tools.favoritesync;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.TreeSet;

/** Builds SQLite fixtures with the exact {@code article_favorites} DDL of V1__create_tables.sql. */
final class TestDb {

  static final String DDL =
      "create table article_favorites ("
          + " article_id varchar(255) not null,"
          + " user_id varchar(255) not null,"
          + " primary key(article_id, user_id))";

  private TestDb() {}

  static Path create(Path dir, String name) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      st.executeUpdate(DDL);
    }
    return file;
  }

  static Path createEmptyFile(Path dir, String name) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      st.executeUpdate("create table unrelated (x int)");
    }
    return file;
  }

  static void insert(Path file, String... keys) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert or ignore into article_favorites (article_id, user_id) values (?, ?)")) {
        for (String k : keys) {
          String[] parts = k.split("\\|");
          ps.setString(1, parts[0]);
          ps.setString(2, parts[1]);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  static void insertMany(Path file, int articles, int usersPerArticle) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert or ignore into article_favorites (article_id, user_id) values (?, ?)")) {
        for (int a = 0; a < articles; a++) {
          for (int u = 0; u < usersPerArticle; u++) {
            ps.setString(1, String.format("article-%06d", a));
            ps.setString(2, String.format("user-%06d", u));
            ps.addBatch();
          }
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  static void delete(Path file, String key) throws SQLException {
    String[] parts = key.split("\\|");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "delete from article_favorites where article_id = ? and user_id = ?")) {
      ps.setString(1, parts[0]);
      ps.setString(2, parts[1]);
      ps.executeUpdate();
    }
  }

  static TreeSet<String> keys(Path file) throws SQLException {
    TreeSet<String> set = new TreeSet<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select article_id, user_id from article_favorites")) {
      while (rs.next()) {
        set.add(rs.getString(1) + "|" + rs.getString(2));
      }
    }
    return set;
  }

  static long count(Path file) throws SQLException {
    return keys(file).size();
  }

  static TreeSet<String> setOf(Collection<String> a, String... more) {
    TreeSet<String> s = new TreeSet<>(a);
    for (String m : more) {
      s.add(m);
    }
    return s;
  }
}
