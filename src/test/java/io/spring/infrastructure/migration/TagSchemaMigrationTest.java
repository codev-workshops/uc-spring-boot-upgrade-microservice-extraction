package io.spring.infrastructure.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TagSchemaMigrationTest {
  private Path dbFile;
  private String url;

  @BeforeEach
  public void setUp() throws IOException {
    dbFile = Files.createTempFile("tag-migration", ".db");
    url = "jdbc:sqlite:" + dbFile;
  }

  @AfterEach
  public void tearDown() throws IOException {
    Files.deleteIfExists(dbFile);
  }

  @Test
  public void should_merge_duplicates_and_drop_invalid_rows() throws SQLException {
    migrate("1", "classpath:db/migration");
    exec(
        "insert into articles (id, slug, created_at) values"
            + " ('a1', 'a1', 0), ('a2', 'a2', 0), ('no-tags', 'no-tags', 0)",
        "insert into tags (id, name) values"
            + " ('t1', 'java'), ('t2', 'java'), ('t3', ' java '), ('t4', 'spring'), ('t5', '  ')",
        "insert into article_tags (article_id, tag_id) values"
            + " ('a1', 't1'), ('a1', 't2'), ('a1', 't3'), ('a1', 't1'),"
            + " ('a2', 't2'), ('a2', 't4'), ('a2', 't5'),"
            + " ('missing-article', 't1'), ('a1', 'missing-tag')");

    migrate(null, "classpath:db/migration");

    Assertions.assertEquals(List.of("t1|java", "t4|spring"), rows("select id, name from tags"));
    Assertions.assertEquals(
        List.of("a1|t1", "a2|t1", "a2|t4"), rows("select article_id, tag_id from article_tags"));
    Assertions.assertEquals(List.of("3"), rows("select count(*) from articles"));
  }

  @Test
  public void should_enforce_constraints_after_migration() throws SQLException {
    migrate(null, "classpath:db/migration");
    exec(
        "insert into articles (id, slug, created_at) values ('a1', 'a1', 0)",
        "insert into tags (id, name) values ('t1', 'java')",
        "insert into article_tags (article_id, tag_id) values ('a1', 't1')");

    assertRejected("insert into tags (id, name) values ('t2', 'java')");
    assertRejected("insert into tags (id, name) values ('t3', ' ')");
    assertRejected("insert into article_tags (article_id, tag_id) values ('a1', 't1')");
    Assertions.assertEquals(
        List.of("idx_article_tags_tag_id"),
        rows(
            "select name from sqlite_master where type = 'index' and tbl_name = 'article_tags'"
                + " and name not like 'sqlite_autoindex%'"));
  }

  @Test
  public void should_preserve_seed_data() throws SQLException {
    migrate("2", "classpath:db/migration", "classpath:db/seed");
    List<String> tagsBefore = rows("select id, name from tags");
    List<String> linksBefore = rows("select article_id, tag_id from article_tags");

    migrate(null, "classpath:db/migration", "classpath:db/seed");

    Assertions.assertEquals(7, tagsBefore.size());
    Assertions.assertEquals(15, linksBefore.size());
    Assertions.assertEquals(tagsBefore, rows("select id, name from tags"));
    Assertions.assertEquals(linksBefore, rows("select article_id, tag_id from article_tags"));
  }

  @Test
  public void should_roll_back_to_v1_schema() throws Exception {
    migrate(null, "classpath:db/migration");
    exec(
        "insert into articles (id, slug, created_at) values ('a1', 'a1', 0)",
        "insert into tags (id, name) values ('t1', 'java')",
        "insert into article_tags (article_id, tag_id) values ('a1', 't1')");

    try (Connection connection = DriverManager.getConnection(url)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        String script = resource("db/rollback/U3__harden_tag_schema.sql").replaceAll("--.*", "");
        for (String sql : script.split(";")) {
          if (!sql.isBlank()) {
            statement.execute(sql);
          }
        }
      }
      connection.commit();
    }

    Assertions.assertEquals(List.of("t1|java"), rows("select id, name from tags"));
    Assertions.assertEquals(List.of("a1|t1"), rows("select article_id, tag_id from article_tags"));
    exec("insert into tags (id, name) values ('t2', 'java')");
    Assertions.assertEquals(
        List.of("1"), rows("select max(cast(version as integer)) from flyway_schema_history"));
  }

  private void migrate(String target, String... locations) {
    Flyway.configure()
        .dataSource(url, "", "")
        .locations(locations)
        .target(target == null ? "latest" : target)
        .load()
        .migrate();
  }

  private void exec(String... statements) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private void assertRejected(String sql) {
    Assertions.assertThrows(SQLException.class, () -> exec(sql), sql);
  }

  private List<String> rows(String sql) throws SQLException {
    List<String> result = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                sql
                    + (sql.contains("count(")
                            || sql.contains("max(")
                            || sql.contains("sqlite_master")
                        ? ""
                        : " order by 1, 2"))) {
      int columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
          values.add(rs.getString(i));
        }
        result.add(String.join("|", values));
      }
    }
    return result;
  }

  private String resource(String path) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
