package io.spring.comments.infrastructure.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One-off import of comments that were stored in the monolith's database before extraction. The
 * monolith's database is attached read-only; startup fails if any row is not present afterwards.
 */
@Slf4j
@Component
public class V3__import_monolith_comments extends BaseJavaMigration {
  private static final String[] LEGACY_TABLES = {"comments_legacy", "comments"};
  private static final String COLUMNS = "id, body, article_id, user_id, created_at, updated_at";

  private final String legacyDbPath;

  public V3__import_monolith_comments(@Value("${comments.legacy.db-path:}") String legacyDbPath) {
    this.legacyDbPath = legacyDbPath == null ? "" : legacyDbPath.trim();
  }

  @Override
  public boolean canExecuteInTransaction() {
    // SQLite cannot ATTACH a database inside a transaction.
    return false;
  }

  @Override
  public void migrate(Context context) throws Exception {
    if (legacyDbPath.isEmpty()) {
      log.info("comments.legacy.db-path not set; skipping import of monolith comments");
      return;
    }
    Path path = Paths.get(legacyDbPath).toAbsolutePath();
    if (!Files.isRegularFile(path)) {
      log.warn("Monolith database {} not found; skipping import of monolith comments", path);
      return;
    }

    Connection connection = context.getConnection();
    try (Statement statement = connection.createStatement()) {
      String uri = "file:" + path + "?mode=ro";
      statement.execute("ATTACH DATABASE '" + uri.replace("'", "''") + "' AS legacy");
      try {
        String table = findLegacyTable(statement);
        if (table == null) {
          log.info("No comments table in {}; nothing to import", path);
          return;
        }
        String source = "legacy." + table;
        int inserted =
            statement.executeUpdate(
                "INSERT OR IGNORE INTO comments ("
                    + COLUMNS
                    + ") SELECT "
                    + COLUMNS
                    + " FROM "
                    + source);
        long missing =
            count(
                statement,
                "SELECT COUNT(*) FROM "
                    + source
                    + " l WHERE NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = l.id)");
        if (missing > 0) {
          throw new IllegalStateException(
              missing + " comments from " + path + " were not imported; refusing to start");
        }
        log.info(
            "Imported {} of {} comments from {}",
            inserted,
            count(statement, "SELECT COUNT(*) FROM " + source),
            path);
      } finally {
        statement.execute("DETACH DATABASE legacy");
      }
    }
  }

  private static String findLegacyTable(Statement statement) throws SQLException {
    for (String table : LEGACY_TABLES) {
      if (count(
              statement,
              "SELECT COUNT(*) FROM legacy.sqlite_master WHERE type = 'table' AND name = '"
                  + table
                  + "'")
          > 0) {
        return table;
      }
    }
    return null;
  }

  private static long count(Statement statement, String sql) throws SQLException {
    try (ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : 0;
    }
  }
}
