package io.spring.tools.favoritesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackfillTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);

  @Test
  void copiesAllRowsAndIsIdempotent() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "article-1|user-2", "article-1|user-3", "article-2|user-1");

    Backfill.Result first = new Backfill(src, dst, 2, out).run();
    assertEquals(3, first.rowsRead);
    assertEquals(3, first.rowsInserted);
    assertEquals(0, first.rowsSkipped);
    assertEquals(2, first.chunks);
    assertEquals(TestDb.keys(src), TestDb.keys(dst));

    Backfill.Result second = new Backfill(src, dst, 2, out).run();
    assertEquals(3, second.rowsRead);
    assertEquals(0, second.rowsInserted);
    assertEquals(3, second.rowsSkipped);
    assertEquals(TestDb.keys(src), TestDb.keys(dst));
    assertTrue(log.toString().contains("backfill T0="));
  }

  @Test
  void restartAfterCrashMidRunConverges() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insertMany(src, 10, 10); // 100 rows, chunk 30 -> 4 chunks

    SQLException boom = new SQLException("simulated crash after chunk 2");
    Backfill crashing =
        new Backfill(
            src,
            dst,
            30,
            out,
            (i, n) -> {
              if (i == 2) {
                throw boom;
              }
            });
    SQLException thrown = assertThrows(SQLException.class, crashing::run);
    assertEquals(boom, thrown);
    assertEquals(60, TestDb.count(dst), "two committed chunks survive the crash");

    Backfill.Result resumed = new Backfill(src, dst, 30, out).run();
    assertEquals(100, resumed.rowsRead);
    assertEquals(40, resumed.rowsInserted);
    assertEquals(60, resumed.rowsSkipped);
    assertEquals(TestDb.keys(src), TestDb.keys(dst));
  }

  @Test
  void emptySourceIsANoOp() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    Backfill.Result r = new Backfill(src, dst, 5000, out).run();
    assertEquals(0, r.rowsRead);
    assertEquals(0, r.chunks);
    assertEquals(0, TestDb.count(dst));
  }

  @Test
  void keepsRowsAlreadyPresentInTarget() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "article-1|user-1");
    TestDb.insert(dst, "article-9|user-9");
    new Backfill(src, dst, 5000, out).run();
    assertEquals(Set.of("article-1|user-1", "article-9|user-9"), TestDb.keys(dst));
  }

  @Test
  void failsClearlyWhenTargetTableMissing() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.createEmptyFile(dir, "favorite.db");
    SyncException e =
        assertThrows(SyncException.class, () -> new Backfill(src, dst, 5000, out).run());
    assertTrue(e.getMessage().contains("article_favorites"), e.getMessage());
    assertTrue(e.getMessage().contains("Flyway"), e.getMessage());
  }

  @Test
  void failsClearlyWhenSourceFileMissing() throws Exception {
    Path dst = TestDb.create(dir, "favorite.db");
    Path missing = dir.resolve("nope.db");
    SyncException e =
        assertThrows(SyncException.class, () -> new Backfill(missing, dst, 5000, out).run());
    assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
  }

  @Test
  void rejectsNonPositiveChunk() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    assertThrows(SyncException.class, () -> new Backfill(src, dst, 0, out));
  }

  @Test
  void tenThousandRowsPerformanceSanity() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insertMany(src, 100, 100);

    long start = System.nanoTime();
    Backfill.Result r = new Backfill(src, dst, 5000, out).run();
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(10_000, r.rowsInserted);
    assertEquals(2, r.chunks);
    assertEquals(10_000, TestDb.count(dst));
    assertTrue(millis < 30_000, "backfill of 10k rows took " + millis + " ms");
  }
}
