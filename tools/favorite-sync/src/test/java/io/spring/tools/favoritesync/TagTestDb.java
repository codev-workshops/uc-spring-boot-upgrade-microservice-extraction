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
 * Builds SQLite fixtures with the exact {@code tags} / {@code article_tags} DDL of
 * V1__create_tables.sql: {@code tags.name} has no unique index and {@code article_tags} has no key
 * at all, so both duplicate names and duplicate pairs are representable.
 */
final class TagTestDb {

  static final String TAGS_DDL =
      "create table tags (id varchar(255) primary key, name varchar(255) not null)";

  static final String ARTICLE_TAGS_DDL =
      "create table article_tags (article_id varchar(255), tag_id varchar(255))";

  private TagTestDb() {}

  static Path create(Path dir, String name) throws SQLException {
    return create(dir, name, true, true);
  }

  static Path create(Path dir, String name, boolean tags, boolean articleTags) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      if (tags) {
        st.executeUpdate(TAGS_DDL);
      }
      if (articleTags) {
        st.executeUpdate(ARTICLE_TAGS_DDL);
      }
    }
    return file;
  }

  static void insertTag(Path file, String id, String name) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement("insert into tags (id, name) values (?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  static void insertPair(Path file, String articleId, String tagId) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement("insert into article_tags (article_id, tag_id) values (?, ?)")) {
      ps.setString(1, articleId);
      ps.setString(2, tagId);
      ps.executeUpdate();
    }
  }

  /** Writes an article with its tags the way {@code MyBatisArticleRepository.createNew} does. */
  static void insertArticleWithTags(Path file, String articleId, String... tagNames)
      throws SQLException {
    for (String name : tagNames) {
      String id = "tag-" + name;
      if (!tagNames(file).contains(name)) {
        insertTag(file, id, name);
      }
      insertPair(file, articleId, id);
    }
  }

  static void insertMany(Path file, int tags, int pairsPerTag) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement t = c.prepareStatement("insert into tags (id, name) values (?, ?)");
          PreparedStatement p =
              c.prepareStatement("insert into article_tags (article_id, tag_id) values (?, ?)")) {
        for (int i = 0; i < tags; i++) {
          String id = String.format("tag-%06d", i);
          t.setString(1, id);
          t.setString(2, "name-" + (i % 977));
          t.addBatch();
          for (int j = 0; j < pairsPerTag; j++) {
            p.setString(1, String.format("article-%06d", (i + j) % tags));
            p.setString(2, id);
            p.addBatch();
          }
        }
        t.executeBatch();
        p.executeBatch();
      }
      c.commit();
    }
  }

  static void updateTagName(Path file, String id, String name) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement("update tags set name = ? where id = ?")) {
      ps.setString(1, name);
      ps.setString(2, id);
      ps.executeUpdate();
    }
  }

  static void deleteTag(Path file, String id) throws SQLException {
    exec(file, "delete from tags where id = ?", id);
  }

  static void deletePairs(Path file, String tagId) throws SQLException {
    exec(file, "delete from article_tags where tag_id = ?", tagId);
  }

  private static void exec(Path file, String sql, String arg) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      ps.executeUpdate();
    }
  }

  /** tag id -> name. */
  static TreeMap<String, String> tags(Path file) throws SQLException {
    TreeMap<String, String> map = new TreeMap<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select id, name from tags")) {
      while (rs.next()) {
        map.put(rs.getString(1), rs.getString(2));
      }
    }
    return map;
  }

  /** {@code GET /tags} order: {@code select name from tags} with no ORDER BY and no DISTINCT. */
  static List<String> tagNames(Path file) throws SQLException {
    return query(file, "select name from tags");
  }

  /** "articleId|tagId" for every stored row, duplicates included, in rowid order. */
  static List<String> pairs(Path file) throws SQLException {
    return query(file, "select article_id || '|' || tag_id from article_tags");
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

  static long countTags(Path file) throws SQLException {
    return tagNames(file).size();
  }

  static long countPairs(Path file) throws SQLException {
    return pairs(file).size();
  }
}
