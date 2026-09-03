package io.spring.tools.favoritesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end CLI tests: argument parsing, exit codes and the rollback flow. */
class MainTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBuf, true);
  private final PrintStream err = new PrintStream(errBuf, true);

  private int run(String... args) {
    return Main.run(args, out, err);
  }

  @Test
  void reportOnlyExitsNonZeroOnDriftAndZeroWhenClean() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1");
    Path report = dir.resolve("out.json");

    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--report",
            report.toString()));
    assertTrue(Files.exists(report));

    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--source=" + src,
            "--target=" + dst,
            "--repair",
            "to-target",
            "--report",
            report.toString()));
    assertEquals(
        Main.EXIT_OK, run("reconcile", "--source", src.toString(), "--target", dst.toString()));
  }

  @Test
  void backfillThenReconcileIsClean() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "b|2");

    assertEquals(
        Main.EXIT_OK,
        run("backfill", "--source", src.toString(), "--target", dst.toString(), "--chunk", "1"));
    assertTrue(outBuf.toString().contains("rowsRead=2 rowsInserted=2 rowsSkipped=0 chunks=2"));
    assertEquals(
        Main.EXIT_OK, run("reconcile", "--source", src.toString(), "--target", dst.toString()));
  }

  @Test
  void reverseBackfillPreservesRowsWrittenOnlyInTheService() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "monolith-only|1");
    TestDb.insert(dst, "a|1", "service-only|2", "service-only|3");

    assertEquals(
        Main.EXIT_OK,
        run("reverse-backfill", "--source", src.toString(), "--target", dst.toString()));

    assertEquals(
        Set.of("a|1", "monolith-only|1", "service-only|2", "service-only|3"), TestDb.keys(src));
    assertEquals(Set.of("a|1", "service-only|2", "service-only|3"), TestDb.keys(dst));
    assertTrue(outBuf.toString().contains("reverse-backfill: copying"));

    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--authoritative",
            "service"),
        "monolith-only row is reported as extra-in-monolith / missing-in-service");
  }

  @Test
  void usageAndArgumentErrorsExitWithTwo() throws Exception {
    assertEquals(Main.EXIT_ERROR, run());
    assertEquals(Main.EXIT_OK, run("--help"));
    assertEquals(Main.EXIT_ERROR, run("frobnicate", "--source", "x"));
    assertEquals(Main.EXIT_ERROR, run("backfill", "--target", "x.db"));
    assertTrue(errBuf.toString().contains("--source is required"));
    assertEquals(
        Main.EXIT_ERROR, run("reconcile", "--source", "a", "--target", "b", "--delete-extras"));
    assertTrue(errBuf.toString().contains("--delete-extras requires"));
    assertEquals(
        Main.EXIT_ERROR,
        run("reconcile", "--source", "a", "--target", "b", "--repair", "sideways"));
    assertEquals(Main.EXIT_ERROR, run("backfill", "--source", "a.db", "--target", "b.db"));
    assertTrue(errBuf.toString().contains("does not exist"));
  }

  @Test
  void missingTableIsAClearError() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.createEmptyFile(dir, "favorite.db");
    assertEquals(
        Main.EXIT_ERROR, run("backfill", "--source", src.toString(), "--target", dst.toString()));
    assertTrue(errBuf.toString().contains("table 'article_favorites' is missing"));
  }
}
