package io.spring.comments.infrastructure.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3__import_monolith_commentsTest {
  @TempDir Path dir;

  private Connection target;

  @BeforeEach
  void setUp() throws Exception {
    target = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("comments.db"));
    exec(
        target,
        "CREATE TABLE comments (id varchar(255) primary key, body text, article_id varchar(255),"
            + " user_id varchar(255), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)",
        "INSERT INTO comments VALUES ('seed', 'seed body', 'a1', 'u1', '2020-01-01', '2020-01-01')");
  }

  @AfterEach
  void tearDown() throws Exception {
    target.close();
  }

  @Test
  void imports_rows_from_retired_monolith_table() throws Exception {
    Path legacy = legacyDb("comments_legacy", "seed", "c1", "c2");

    migrate(legacy.toString());

    assertEquals(3, count("SELECT COUNT(*) FROM comments"));
    assertEquals(1, count("SELECT COUNT(*) FROM comments WHERE id = 'c1' AND user_id = 'u-c1'"));
  }

  @Test
  void imports_rows_from_not_yet_renamed_monolith_table() throws Exception {
    Path legacy = legacyDb("comments", "c1");

    migrate(legacy.toString());

    assertEquals(2, count("SELECT COUNT(*) FROM comments"));
  }

  @Test
  void skips_when_path_not_configured_or_missing() throws Exception {
    migrate("");
    migrate(dir.resolve("missing.db").toString());

    assertEquals(1, count("SELECT COUNT(*) FROM comments"));
  }

  @Test
  void fails_when_rows_cannot_be_imported() throws Exception {
    Path legacy = legacyDb("comments_legacy", "c1");
    exec(target, "CREATE TRIGGER block BEFORE INSERT ON comments BEGIN SELECT RAISE(IGNORE); END");

    assertThrows(IllegalStateException.class, () -> migrate(legacy.toString()));
  }

  private Path legacyDb(String table, String... ids) throws Exception {
    Path path = dir.resolve("monolith.db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
      exec(
          c,
          "CREATE TABLE "
              + table
              + " (id varchar(255) primary key, body text, article_id varchar(255),"
              + " user_id varchar(255), created_at TIMESTAMP NOT NULL,"
              + " updated_at TIMESTAMP NOT NULL)");
      for (String id : ids) {
        exec(
            c,
            "INSERT INTO "
                + table
                + " VALUES ('"
                + id
                + "', 'body', 'a1', 'u-"
                + id
                + "', '2021-01-01', '2021-01-01')");
      }
    }
    return path;
  }

  private void migrate(String legacyPath) throws Exception {
    new V3__import_monolith_comments(legacyPath)
        .migrate(
            new Context() {
              @Override
              public Configuration getConfiguration() {
                return null;
              }

              @Override
              public Connection getConnection() {
                return target;
              }
            });
  }

  private long count(String sql) throws Exception {
    try (Statement s = target.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      return rs.getLong(1);
    }
  }

  private static void exec(Connection c, String... sqls) throws Exception {
    try (Statement s = c.createStatement()) {
      for (String sql : sqls) {
        s.execute(sql);
      }
    }
  }
}
