package io.spring.tools.favoritesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --domain comment}: backfill, reconcile/repair, reverse-backfill and CLI wiring. */
class CommentSyncTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errLog = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);
  private final PrintStream err = new PrintStream(errLog, true);

  private Reconcile.Options opts(Path src, Path dst) {
    Reconcile.Options o = new Reconcile.Options();
    o.domain = Domain.COMMENT;
    o.source = src;
    o.target = dst;
    return o;
  }

  private Backfill backfill(Path src, Path dst, int chunk) {
    return new Backfill(Domain.COMMENT, src, dst, chunk, out);
  }

  private int run(String... args) {
    return Main.run(args, out, err);
  }

  // ---------------------------------------------------------------- backfill

  @Test
  void backfillCopiesAllColumnsWithExactTimestampsAndIsIdempotent() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insertMonolithStyle(
        src, "c-1", "line one\nline \"two\" 'three'", "article-1", "user-2", 1_700_000_000_123L);
    CommentTestDb.insertSeedStyle(
        src, "c-2", "seeded", "article-1", "user-3", "datetime('now', '-6 days')");
    CommentTestDb.insertMonolithStyle(src, "c-3", "", "article-2", "user-1", 1_700_000_999_999L);

    Backfill.Result first = backfill(src, dst, 2).run();
    assertEquals(3, first.rowsRead);
    assertEquals(3, first.rowsInserted);
    assertEquals(0, first.rowsSkipped);
    assertEquals(2, first.chunks);
    Map<String, String> copied = CommentTestDb.rows(dst);
    assertEquals(CommentTestDb.rows(src), copied, "every column and SQLite storage class match");
    assertTrue(copied.get("c-1").startsWith("integer:1700000000123|integer:1700000000123|"));
    assertTrue(copied.get("c-2").startsWith("text:"), "seed-style TEXT timestamp kept as TEXT");
    assertTrue(log.toString().contains("backfill domain=comment table=comments"));
    assertTrue(log.toString().contains("backfill T0="));

    Backfill.Result second = backfill(src, dst, 2).run();
    assertEquals(3, second.rowsRead);
    assertEquals(0, second.rowsInserted);
    assertEquals(3, second.rowsSkipped);
    assertEquals(CommentTestDb.rows(src), CommentTestDb.rows(dst));
  }

  @Test
  void backfillRestartAfterCrashMidRunConverges() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insertMany(src, 100); // chunk 30 -> 4 chunks

    SQLException boom = new SQLException("simulated crash after chunk 2");
    Backfill crashing =
        new Backfill(
            Domain.COMMENT,
            src,
            dst,
            30,
            out,
            (i, n) -> {
              if (i == 2) {
                throw boom;
              }
            });
    assertEquals(boom, assertThrows(SQLException.class, crashing::run));
    assertEquals(60, CommentTestDb.count(dst), "two committed chunks survive the crash");

    Backfill.Result resumed = backfill(src, dst, 30).run();
    assertEquals(100, resumed.rowsRead);
    assertEquals(40, resumed.rowsInserted);
    assertEquals(60, resumed.rowsSkipped);
    assertEquals(CommentTestDb.rows(src), CommentTestDb.rows(dst));
  }

  @Test
  void backfillDoesNotOverwriteRowsAlreadyInTarget() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insert(src, "c-1");
    CommentTestDb.insert(dst, "c-1");
    CommentTestDb.updateBody(dst, "c-1", "edited in service");
    CommentTestDb.insert(dst, "service-only");

    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsInserted);
    assertEquals(1, r.rowsSkipped);
    assertTrue(CommentTestDb.rows(dst).get("c-1").endsWith("|edited in service|article-1|user-1"));
    assertEquals(List.of("c-1", "service-only"), CommentTestDb.ids(dst));
  }

  @Test
  void backfillEmptyTablesIsANoOp() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsRead);
    assertEquals(0, r.chunks);
    assertEquals(0, CommentTestDb.count(dst));
  }

  @Test
  void backfillFailsClearlyWhenCommentsTableMissing() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db"); // has article_favorites but no comments
    SyncException e = assertThrows(SyncException.class, () -> backfill(src, dst, 5000).run());
    assertTrue(e.getMessage().contains("table 'comments' is missing"), e.getMessage());
  }

  @Test
  void backfillTenThousandRowsPerformanceSanity() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insertMany(src, 10_000);

    long start = System.nanoTime();
    Backfill.Result r = backfill(src, dst, 5000).run();
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(10_000, r.rowsInserted);
    assertEquals(2, r.chunks);
    assertEquals(10_000, CommentTestDb.count(dst));
    assertTrue(millis < 30_000, "backfill of 10k comments took " + millis + " ms");
  }

  // --------------------------------------------------------------- reconcile

  @Test
  void reconcileDetectsMissingExtraAndDivergedBodyAndWritesReport() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    for (String id : List.of("c-1", "c-2", "c-3")) {
      CommentTestDb.insert(src, id);
    }
    CommentTestDb.insert(dst, "c-1");
    CommentTestDb.insert(dst, "c-2");
    CommentTestDb.insert(dst, "c-9");
    CommentTestDb.updateBody(dst, "c-2", "tampered");

    Reconcile.Options o = opts(src, dst);
    o.report = dir.resolve("reports/out.json");
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(3, r.before.driftRows());
    assertEquals("c-3", r.before.missingInTarget.get(0).toString());
    assertEquals("c-9", r.before.extraInTarget.get(0).toString());
    assertEquals("c-2", r.before.diverged.get(0).source.toString());
    assertEquals(List.of("body"), r.before.diverged.get(0).columns);
    assertNull(r.after);

    JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(o.report));
    assertEquals("comment", json.get("domain").asText());
    assertEquals("monolith", json.get("authoritative").asText());
    assertEquals(0, json.get("graceSeconds").asInt());
    JsonNode table = json.get("tables").get(0);
    assertEquals("comments", table.get("table").asText());
    assertEquals(3, table.get("monolithCount").asLong());
    assertEquals(3, table.get("serviceCount").asLong());
    assertEquals("c-3", table.get("missingInService").get(0).get("id").asText());
    assertEquals(1, table.get("missingInServiceTotal").asInt());
    assertEquals("c-9", table.get("extraInService").get(0).get("id").asText());
    assertEquals("c-2", table.get("diverged").get(0).get("id").asText());
    assertEquals("body", table.get("diverged").get(0).get("columns").get(0).asText());
    assertEquals(1, table.get("divergedTotal").asInt());
    assertEquals("DRIFT", table.get("status").asText());
    assertEquals(64, table.get("monolithChecksum").asText().length());
    assertEquals(3, json.get("summary").get("driftRows").asInt());
    assertEquals("DRIFT", json.get("summary").get("status").asText());
    assertNull(json.get("repair"));
    assertTrue(
        log.toString()
            .contains(
                "reconcile domain=comment table=comments phase=before monolith=3 service=3"
                    + " missing=1 extra=1 diverged=1 status=DRIFT"));
  }

  @Test
  void reconcileFlagsDivergedTimestampNotJustBody() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insertMonolithStyle(src, "c-1", "same", "a", "u", 1_700_000_000_000L);
    CommentTestDb.insertMonolithStyle(dst, "c-1", "same", "a", "u", 1_700_000_000_001L);
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(1, r.before.diverged.size());
    assertEquals(List.of("created_at", "updated_at"), r.before.diverged.get(0).columns);
  }

  @Test
  void reconcileIdenticalAndEmptyTablesAreClean() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    Reconcile.Outcome empty = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, empty.remainingDrift());
    assertEquals(empty.before.sourceChecksum, empty.before.targetChecksum);
    assertEquals("CLEAN", empty.report.get("summary").get("status").asText());

    CommentTestDb.insertSeedStyle(src, "c-1", "x", "a", "u", "datetime('2026-01-01 10:00:00')");
    CommentTestDb.insertSeedStyle(dst, "c-1", "x", "a", "u", "datetime('2026-01-01 10:00:00')");
    CommentTestDb.insert(src, "c-2");
    CommentTestDb.insert(dst, "c-2");
    Reconcile.Outcome same = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, same.remainingDrift());
    assertEquals(same.before.sourceChecksum, same.before.targetChecksum);
  }

  @Test
  void repairToTargetInsertsAndOverwritesDivergedButKeepsExtrasByDefault() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    for (String id : List.of("c-1", "c-2", "c-3")) {
      CommentTestDb.insert(src, id);
    }
    CommentTestDb.insert(dst, "c-1");
    CommentTestDb.insert(dst, "c-2");
    CommentTestDb.insert(dst, "z-9");
    CommentTestDb.updateBody(dst, "c-2", "tampered");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(1, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(0, r.deleted);
    assertEquals(List.of("c-1", "c-2", "c-3", "z-9"), CommentTestDb.ids(dst));
    assertEquals(CommentTestDb.rows(src).get("c-2"), CommentTestDb.rows(dst).get("c-2"));
    assertEquals(List.of("c-1", "c-2", "c-3"), CommentTestDb.ids(src), "source untouched");
    assertEquals(1, r.remainingDrift(), "the extra row is still reported");
    JsonNode repair = r.report.get("repair");
    assertEquals("to-target", repair.get("mode").asText());
    assertEquals(1, repair.get("updated").asInt());
    assertEquals(0, repair.get("missingInServiceAfter").asInt());
    assertEquals(0, repair.get("divergedAfter").asInt());
    assertEquals(1, repair.get("extraInServiceAfter").asInt());
    assertTrue(log.toString().contains("extras kept; pass --delete-extras"));
  }

  @Test
  void repairToTargetWithDeleteExtrasConvergesToZeroDrift() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    for (String id : List.of("c-1", "c-2", "c-3")) {
      CommentTestDb.insert(src, id);
    }
    CommentTestDb.insert(dst, "c-1");
    CommentTestDb.insert(dst, "c-2");
    CommentTestDb.insert(dst, "z-9");
    CommentTestDb.updateBody(dst, "c-2", "tampered");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(1, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(1, r.deleted);
    assertEquals(0, r.remainingDrift());
    assertEquals(CommentTestDb.rows(src), CommentTestDb.rows(dst));
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());
    assertEquals(0, new Reconcile(opts(src, dst), out).run().remainingDrift());
  }

  @Test
  void repairToSourceCopiesServiceRowsIntoMonolithAndOverwritesDiverged() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insert(src, "c-1");
    CommentTestDb.insert(src, "only-monolith");
    CommentTestDb.insert(dst, "c-1");
    CommentTestDb.insert(dst, "only-service");
    CommentTestDb.updateBody(dst, "c-1", "edited in service (authoritative)");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_SOURCE;
    o.authoritative = "service";
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(1, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(0, r.deleted);
    assertEquals(List.of("c-1", "only-monolith", "only-service"), CommentTestDb.ids(src));
    assertEquals(CommentTestDb.rows(dst).get("c-1"), CommentTestDb.rows(src).get("c-1"));
    assertEquals(List.of("c-1", "only-service"), CommentTestDb.ids(dst), "service untouched");
    assertEquals("service", r.report.get("authoritative").asText());
    assertEquals(0, r.after.extraInTarget.size());
    assertEquals(0, r.after.diverged.size());
    assertEquals(1, r.after.missingInTarget.size(), "monolith-only row kept");

    o.deleteExtras = true;
    Reconcile.Outcome again = new Reconcile(o, out).run();
    assertEquals(1, again.deleted);
    assertEquals(0, again.remainingDrift());
    assertEquals(CommentTestDb.rows(dst), CommentTestDb.rows(src));
  }

  @Test
  void repairNeverDeletesWithoutDeleteExtras() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insert(dst, "service-only-1");
    CommentTestDb.insert(dst, "service-only-2");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(0, r.deleted);
    assertEquals(2, CommentTestDb.count(dst));
    assertEquals(2, r.remainingDrift());
    assertFalse(r.report.get("repair").get("deleteExtras").asBoolean());

    assertEquals(
        Main.EXIT_ERROR,
        run(
            "reconcile",
            "--domain",
            "comment",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--delete-extras"));
    assertTrue(errLog.toString().contains("--delete-extras requires"));
    assertEquals(2, CommentTestDb.count(dst));
  }

  @Test
  void repairAbortsAboveMaxRepairIncludingDivergedRows() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    for (String id : List.of("c-1", "c-2", "c-3")) {
      CommentTestDb.insert(src, id);
      CommentTestDb.insert(dst, id);
      CommentTestDb.updateBody(dst, id, "drifted");
    }

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.maxRepair = 2;
    SyncException e = assertThrows(SyncException.class, () -> new Reconcile(o, out).run());
    assertTrue(e.getMessage().contains("--max-repair"), e.getMessage());
    assertTrue(
        CommentTestDb.rows(dst).values().stream().allMatch(v -> v.contains("|drifted|")),
        "nothing written when the guard trips");
  }

  @Test
  void reconcileTenThousandRowsQuickly() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insertMany(src, 10_000);
    CommentTestDb.insertMany(dst, 10_000);
    CommentTestDb.delete(dst, "comment-000050");
    CommentTestDb.updateBody(dst, "comment-007000", "drift");

    long start = System.nanoTime();
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(2, r.remainingDrift());
    assertEquals("comment-000050", r.before.missingInTarget.get(0).toString());
    assertEquals("comment-007000", r.before.diverged.get(0).source.toString());
    assertFalse(r.report.get("tables").get(0).has("truncated"));
    assertTrue(millis < 30_000, "reconcile of 10k comments took " + millis + " ms");
  }

  // --------------------------------------------------------------------- CLI

  @Test
  void cliBackfillThenReconcileIsCleanAndReverseBackfillRestoresServiceOnlyRows() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    CommentTestDb.insert(src, "c-1");
    CommentTestDb.insertSeedStyle(src, "c-2", "seed", "a", "u", "datetime('now', '-1 day')");
    Path report = dir.resolve("out.json");

    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain=comment",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--chunk",
            "1"));
    assertTrue(log.toString().contains("rowsRead=2 rowsInserted=2 rowsSkipped=0 chunks=2"));
    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "comment",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--report",
            report.toString()));
    assertEquals(
        "comments",
        new ObjectMapper()
            .readTree(Files.readAllBytes(report))
            .get("tables")
            .get(0)
            .get("table")
            .asText());

    // state C: writes land only in the service, then roll back
    CommentTestDb.insert(dst, "written-in-state-c");
    CommentTestDb.delete(dst, "c-1");
    assertEquals(
        Main.EXIT_OK,
        run(
            "reverse-backfill",
            "--domain",
            "comment",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    assertTrue(log.toString().contains("reverse-backfill: copying"));
    assertEquals(List.of("c-1", "c-2", "written-in-state-c"), CommentTestDb.ids(src));
    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--domain",
            "comment",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--authoritative",
            "service"),
        "c-1 deleted in state C is reported as missing-in-service");
    assertTrue(log.toString().contains("missing=1 extra=0 diverged=0 status=DRIFT"));
  }

  @Test
  void cliRejectsUnknownDomainAndDefaultsToFavorite() throws Exception {
    Path src = CommentTestDb.create(dir, "dev.db");
    Path dst = CommentTestDb.create(dir, "comment.db");
    assertEquals(
        Main.EXIT_ERROR,
        run(
            "reconcile",
            "--domain",
            "profile",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    assertTrue(errLog.toString().contains("--domain must be one of favorite|comment|tag|article"));

    assertEquals(
        Main.EXIT_ERROR, run("reconcile", "--source", src.toString(), "--target", dst.toString()));
    assertTrue(
        errLog.toString().contains("table 'article_favorites' is missing"),
        "without --domain the favorite table is expected");
  }
}
