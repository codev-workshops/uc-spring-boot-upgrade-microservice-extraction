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
 * Builds SQLite fixtures with the exact {@code users} / {@code follows} DDL of
 * V1__create_tables.sql: {@code users.id} primary key, {@code username} and {@code email} UNIQUE,
 * {@code follows(user_id, follow_id)} with no key and no FKs.
 */
final class UserTestDb {

  static final String USERS_DDL =
      "create table users ("
          + " id varchar(255) primary key,"
          + " username varchar(255) UNIQUE,"
          + " password varchar(255),"
          + " email varchar(255) UNIQUE,"
          + " bio text,"
          + " image varchar(511))";

  static final String FOLLOWS_DDL =
      "create table follows (user_id varchar(255) not null, follow_id varchar(255) not null)";

  /** Shape of what {@code BCryptPasswordEncoder} stores; never a real credential. */
  static String hash(String id) {
    return "$2a$10$" + String.format("%-53s", "hash-of-" + id).replace(' ', 'x');
  }

  private UserTestDb() {}

  static Path create(Path dir, String name) throws SQLException {
    return create(dir, name, true, true);
  }

  static Path create(Path dir, String name, boolean users, boolean follows) throws SQLException {
    Path file = dir.resolve(name);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      if (users) {
        st.executeUpdate(USERS_DDL);
      }
      if (follows) {
        st.executeUpdate(FOLLOWS_DDL);
      }
    }
    return file;
  }

  /** The way {@code UserService.createUser} stores a row: empty bio, default image. */
  static void insertUser(Path file, String id) throws SQLException {
    insertUser(
        file,
        id,
        "name-" + id,
        hash(id),
        id + "@example.com",
        "",
        "https://static.productionready.io/images/smiley-cyrus.jpg");
  }

  static void insertUser(
      Path file,
      String id,
      String username,
      String password,
      String email,
      String bio,
      String image)
      throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement(
                "insert into users (id, username, password, email, bio, image)"
                    + " values (?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, username);
      ps.setString(3, password);
      ps.setString(4, email);
      ps.setString(5, bio);
      ps.setString(6, image);
      ps.executeUpdate();
    }
  }

  /** The way {@code UserMapper.xml#saveRelation} stores a follow: a bare pair, no dedup. */
  static void insertFollow(Path file, String userId, String followId) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement("insert into follows (user_id, follow_id) values (?, ?)")) {
      ps.setString(1, userId);
      ps.setString(2, followId);
      ps.executeUpdate();
    }
  }

  static void insertMany(Path file, int users, int followsPerUser) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      c.setAutoCommit(false);
      try (PreparedStatement ps =
          c.prepareStatement(
              "insert into users (id, username, password, email, bio, image)"
                  + " values (?, ?, ?, ?, ?, ?)")) {
        for (int i = 0; i < users; i++) {
          String id = String.format("user-%06d", i);
          ps.setString(1, id);
          ps.setString(2, "name-" + i);
          ps.setString(3, hash(id));
          ps.setString(4, "u" + i + "@example.com");
          ps.setString(5, i % 3 == 0 ? "" : "bio with \"quotes\" and 'apostrophes' " + i);
          ps.setString(6, i % 5 == 0 ? null : "https://img.example.com/" + i + ".png");
          ps.addBatch();
        }
        ps.executeBatch();
      }
      try (PreparedStatement ps =
          c.prepareStatement("insert into follows (user_id, follow_id) values (?, ?)")) {
        for (int i = 0; i < users; i++) {
          for (int j = 1; j <= followsPerUser; j++) {
            ps.setString(1, String.format("user-%06d", i));
            ps.setString(2, String.format("user-%06d", (i + j) % users));
            ps.addBatch();
          }
        }
        ps.executeBatch();
      }
      c.commit();
    }
  }

  static void updateUser(Path file, String id, String column, Object value) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps =
            c.prepareStatement("update users set " + column + " = ? where id = ?")) {
      ps.setObject(1, value);
      ps.setString(2, id);
      ps.executeUpdate();
    }
  }

  static void deleteUser(Path file, String id) throws SQLException {
    exec(file, "delete from users where id = ?", id);
  }

  static void deleteFollows(Path file, String userId) throws SQLException {
    exec(file, "delete from follows where user_id = ?", userId);
  }

  private static void exec(Path file, String sql, String arg) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      ps.executeUpdate();
    }
  }

  static List<String> userIds(Path file) throws SQLException {
    return query(file, "select id from users order by id");
  }

  /** id -> every payload column, for exact copy assertions. */
  static TreeMap<String, String> users(Path file) throws SQLException {
    TreeMap<String, String> map = new TreeMap<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("select id, username, password, email, bio, image from users")) {
      while (rs.next()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i <= 6; i++) {
          sb.append(rs.getString(i)).append('|');
        }
        map.put(rs.getString(1), sb.toString());
      }
    }
    return map;
  }

  /** Every stored pair, duplicates included, as {@code user_id>follow_id}. */
  static List<String> follows(Path file) throws SQLException {
    return query(
        file, "select user_id || '>' || follow_id from follows order by user_id, follow_id");
  }

  static String columnOf(Path file, String id, String column) throws SQLException {
    List<String> v = query(file, "select " + column + " from users where id = '" + id + "'");
    return v.isEmpty() ? null : v.get(0);
  }

  static long countUsers(Path file) throws SQLException {
    return userIds(file).size();
  }

  static long countFollows(Path file) throws SQLException {
    return follows(file).size();
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
