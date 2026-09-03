package io.spring.tools.favoritesync;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Builds SQLite fixtures with the exact {@code articles} DDL of V1__create_tables.sql /
 * V1__create_article_tables.sql: {@code id} primary key, {@code slug} UNIQUE, no FKs. The tag
 * tables are created too (they live in the same {@code article.db}) so tests can prove that {@code
 * --domain article} leaves them alone.
 */
final class ArticleTestDb {

  static final String ARTICLES_DDL =
      "create table articles ("
          + " id varchar(255) primary key,"
          + " user_id varchar(255),"
          + " slug varchar(255) UNIQUE,"
          + " title varchar(255),"
          + " description text,"
          + " body text,"
          + " created_at TIMESTAMP NOT NULL,"
          + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";

  private ArticleTestDb() {}

  static Path create(Path dir, String name) throws SQLException {
    return create(dir, name, true);
  }

  static Path create(Path dir, String name, boolean articles) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      if (articles) {
        st.executeUpdate(ARTICLES_DDL);
      }
      st.executeUpdate(TagTestDb.TAGS_DDL);
      st.executeUpdate(TagTestDb.ARTICLE_TAGS_DDL);
    }
    return file;
  }

  /** The way {@code ArticleMapper.xml#insert} stores a row: INTEGER epoch-millis timestamps. */
  static void insert(Path file, String id) throws SQLException {
    insert(file, id, "user-1", "slug-" + id, "Title " + id, "desc " + id, "body " + id);
  }

  static void insert(
      Path file,
      String id,
      String userId,
      String slug,
      String title,
      String description,
      String body)
      throws SQLException {
    long now = 1_756_872_000_000L + id.hashCode() % 100_000;
    insertRaw(file, id, userId, slug, title, description, body, now, now);
  }

  /** The way {@code V2__seed_data.sql} stores a row: TEXT timestamps via {@code datetime(...)}. */
  static void insertSeedStyle(Path file, String id, String slug, String createdAtExpr)
      throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "insert into articles (id, user_id, slug, title, description, body, created_at,"
                    + " updated_at) values (?, 'seed-user', ?, 'Seed', 'seed', 'seed body', "
                    + createdAtExpr
                    + ", "
                    + createdAtExpr
                    + ")")) {
      ps.setString(1, id);
      ps.setString(2, slug);
      ps.executeUpdate();
    }
  }

  static void insertRaw(
      Path file,
      String id,
      String userId,
      String slug,
      String title,
      String description,
      String body,
      Object createdAt,
      Object updatedAt)
      throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "insert into articles (id, user_id, slug, title, description, body, created_at,"
                    + " updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, userId);
      ps.setString(3, slug);
      ps.setString(4, title);
      ps.setString(5, description);
      ps.setString(6, body);
      ps.setObject(7, createdAt);
      ps.setObject(8, updatedAt);
      ps.executeUpdate();
    }
  }

  static void insertMany(Path file, int n) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert into articles (id, user_id, slug, title, description, body, created_at,"
                  + " updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
        for (int i = 0; i < n; i++) {
          String id = String.format("article-%06d", i);
          ps.setString(1, id);
          ps.setString(2, "user-" + (i % 37));
          ps.setString(3, "slug-" + i);
          ps.setString(4, "Title " + i);
          ps.setString(5, "description " + i);
          ps.setString(6, "body\nwith \"quotes\" and 'apostrophes' " + i);
          ps.setLong(7, 1_756_872_000_000L + i * 1000L);
          ps.setLong(8, 1_756_872_000_000L + i * 1000L);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  static void update(Path file, String id, String column, Object value) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement("update articles set " + column + " = ? where id = ?")) {
      ps.setObject(1, value);
      ps.setString(2, id);
      ps.executeUpdate();
    }
  }

  static void delete(Path file, String id) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement("delete from articles where id = ?")) {
      ps.setString(1, id);
      ps.executeUpdate();
    }
  }

  static List<String> ids(Path file) throws SQLException {
    return query(file, "select id from articles order by id");
  }

  /** id -> every column with its storage class, for exact copy assertions. */
  static TreeMap<String, String> rows(Path file) throws SQLException {
    TreeMap<String, String> map = new TreeMap<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select id, user_id, slug, title, description, body,"
                    + " typeof(created_at), created_at, typeof(updated_at), updated_at"
                    + " from articles")) {
      while (rs.next()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i <= 10; i++) {
          sb.append(rs.getString(i)).append('|');
        }
        map.put(rs.getString(1), sb.toString());
      }
    }
    return map;
  }

  static String slugOf(Path file, String id) throws SQLException {
    List<String> v = query(file, "select slug from articles where id = '" + id + "'");
    return v.isEmpty() ? null : v.get(0);
  }

  static long count(Path file) throws SQLException {
    return ids(file).size();
  }

  private static List<String> query(Path file, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
