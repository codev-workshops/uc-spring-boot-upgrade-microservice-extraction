package io.spring.tools.favoritesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --domain article}: the {@code articles} table keyed by {@code id}, all seven payload
 * columns compared as stored, tag tables untouched, and {@code slug} UNIQUE clashes reported as
 * conflicts instead of crashing.
 */
class ArticleSyncTest {

  private static final List<String> PAYLOAD =
      List.of("user_id", "slug", "title", "description", "body", "created_at", "updated_at");

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errLog = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);
  private final PrintStream err = new PrintStream(errLog, true);

  private Reconcile.Options opts(Path src, Path dst) {
    Reconcile.Options o = new Reconcile.Options();
    o.domain = Domain.ARTICLE;
    o.source = src;
    o.target = dst;
    return o;
  }

  private Backfill backfill(Path src, Path dst, int chunk) {
    return new Backfill(Domain.ARTICLE, src, dst, chunk, out);
  }

  private int run(String... args) {
    return Main.run(args, out, err);
  }

  // ---------------------------------------------------------------- domain

  @Test
  void domainCoversOnlyTheArticlesTable() {
    assertEquals(Domain.ARTICLE, Domain.parse("article"));
    assertEquals(1, Domain.ARTICLE.tables.size());
    SyncTable t = Domain.ARTICLE.tables.get(0);
    assertEquals("articles", t.table);
    assertEquals(List.of("id"), t.keyColumns);
    assertEquals(PAYLOAD, t.payloadColumns);
    assertEquals(List.of("slug"), t.uniqueColumns);
    assertTrue(t.uniqueKey);
  }

  // ---------------------------------------------------------------- backfill

  @Test
  void backfillCopiesRowsVerbatimAndIsIdempotent() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1");
    ArticleTestDb.insert(src, "a2", "user-2", "second", "Second", null, "");
    ArticleTestDb.insertSeedStyle(src, "a3", "seed-slug", "datetime('now', '-1 day')");
    ArticleTestDb.insertRaw(
        src,
        "a4",
        "user-4",
        "text-ts",
        "T",
        "d",
        "b",
        "2024-01-02 03:04:05",
        "2024-01-02T03:04:05Z");
    TagTestDb.insertArticleWithTags(src, "a1", "java");

    Backfill.Result first = backfill(src, dst, 2).run();
    assertEquals(4, first.rowsRead);
    assertEquals(4, first.rowsInserted);
    assertEquals(0, first.rowsSkipped);
    assertEquals(0, first.conflicts);
    assertEquals(1, first.tables.size());
    assertEquals("articles", first.tables.get(0).table);
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst), "values + storage classes");
    assertTrue(ArticleTestDb.rows(dst).get("a1").contains("|integer|"));
    assertTrue(ArticleTestDb.rows(dst).get("a3").contains("|text|"));
    assertTrue(log.toString().contains("backfill T0="));
    assertEquals(0, TagTestDb.countTags(dst), "--domain article never touches tags");
    assertEquals(0, TagTestDb.countPairs(dst), "--domain article never touches article_tags");

    Backfill.Result second = backfill(src, dst, 2).run();
    assertEquals(4, second.rowsRead);
    assertEquals(0, second.rowsInserted);
    assertEquals(4, second.rowsSkipped);
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
  }

  @Test
  void backfillRestartAfterCrashMidRunConverges() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insertMany(src, 100);

    SQLException boom = new SQLException("simulated crash after chunk 3");
    Backfill crashing =
        new Backfill(
            Domain.ARTICLE,
            src,
            dst,
            30,
            out,
            (i, n) -> {
              if (i == 3) {
                throw boom;
              }
            });
    assertEquals(boom, assertThrows(SQLException.class, crashing::run));
    assertEquals(90, ArticleTestDb.count(dst), "three committed chunks survive");

    Backfill.Result resumed = backfill(src, dst, 30).run();
    assertEquals(100, resumed.rowsRead);
    assertEquals(10, resumed.rowsInserted);
    assertEquals(90, resumed.rowsSkipped);
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
  }

  @Test
  void backfillKeepsRowsAlreadyPresentInTargetAndServiceOnlyRows() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1");
    ArticleTestDb.insert(dst, "a1", "user-1", "slug-a1", "Edited in service", "d", "b");
    ArticleTestDb.insert(dst, "service-only");

    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsInserted);
    assertEquals(1, r.rowsSkipped);
    assertEquals(0, r.conflicts);
    assertEquals(List.of("a1", "service-only"), ArticleTestDb.ids(dst));
    assertTrue(ArticleTestDb.rows(dst).get("a1").contains("Edited in service"));
  }

  @Test
  void backfillEmptyTableIsANoOp() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(0, r.rowsRead);
    assertEquals(0, r.rowsInserted);
    assertEquals(0, r.conflicts);
    assertEquals(0, ArticleTestDb.count(dst));
  }

  @Test
  void backfillReportsSlugClashAsConflictInsteadOfCrashing() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1", "user-1", "how-to-train-your-dragon", "T", "d", "b");
    ArticleTestDb.insert(src, "a2");
    ArticleTestDb.insert(dst, "svc-9", "user-1", "how-to-train-your-dragon", "T", "d", "b");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(2, r.rowsRead);
    assertEquals(1, r.rowsInserted, "the non-clashing row still lands");
    assertEquals(1, r.rowsSkipped);
    assertEquals(1, r.conflicts);
    Conflict c = r.tables.get(0).conflicts.get(0);
    assertEquals("slug", c.column);
    assertEquals("how-to-train-your-dragon", c.value);
    assertEquals("svc-9", c.conflictingKey);
    assertEquals("a1", c.row.key[0]);
    assertEquals(List.of("a2", "svc-9"), ArticleTestDb.ids(dst));
    assertTrue(log.toString().contains("backfill conflict table=articles"));
    assertTrue(log.toString().contains("conflicts=1"));
  }

  @Test
  void backfillCliExitsWithDriftWhenASlugClashRemains() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1", "u", "same", "T", "d", "b");
    ArticleTestDb.insert(dst, "a9", "u", "same", "T", "d", "b");

    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "backfill",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    ArticleTestDb.delete(dst, "a9");
    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    assertEquals(List.of("a1"), ArticleTestDb.ids(dst));
  }

  @Test
  void reverseBackfillCopiesServiceRowsBackIntoTheMonolith() throws Exception {
    Path mono = ArticleTestDb.create(dir, "dev.db");
    Path svc = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(mono, "a1");
    ArticleTestDb.insert(svc, "a1");
    ArticleTestDb.insert(svc, "written-after-cutover", "user-7", "new-post", "New", "d", "b");
    TagTestDb.insertArticleWithTags(svc, "written-after-cutover", "spring");

    int exit =
        run(
            "reverse-backfill",
            "--domain",
            "article",
            "--source",
            mono.toString(),
            "--target",
            svc.toString());
    assertEquals(Main.EXIT_OK, exit);
    assertEquals(ArticleTestDb.rows(svc), ArticleTestDb.rows(mono));
    assertEquals(0, TagTestDb.countTags(mono), "tag tables are --domain tag's job");
    assertTrue(log.toString().contains("reverse-backfill"));
  }

  // ---------------------------------------------------------------- reconcile

  @Test
  void reconcileReportsMissingExtraAndEveryDivergedColumn() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    for (int i = 0; i < PAYLOAD.size(); i++) {
      ArticleTestDb.insert(src, "d" + i);
      ArticleTestDb.insert(dst, "d" + i);
    }
    ArticleTestDb.update(dst, "d0", "user_id", "someone-else");
    ArticleTestDb.update(dst, "d1", "slug", "renamed-slug");
    ArticleTestDb.update(dst, "d2", "title", "Other title");
    ArticleTestDb.update(dst, "d3", "description", null);
    ArticleTestDb.update(dst, "d4", "body", "edited body");
    ArticleTestDb.update(dst, "d5", "created_at", "2024-01-01 00:00:00");
    ArticleTestDb.update(dst, "d6", "updated_at", 1L);
    ArticleTestDb.insert(src, "same");
    ArticleTestDb.insert(dst, "same");
    ArticleTestDb.insert(src, "missing");
    ArticleTestDb.insert(dst, "extra");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    Reconcile.Diff d = r.before;
    assertEquals(9, d.sourceCount);
    assertEquals(9, d.targetCount);
    assertEquals(1, d.missingInTarget.size());
    assertEquals("missing", d.missingInTarget.get(0).key[0]);
    assertEquals(1, d.extraInTarget.size());
    assertEquals("extra", d.extraInTarget.get(0).key[0]);
    assertEquals(7, d.diverged.size());
    for (int i = 0; i < PAYLOAD.size(); i++) {
      assertEquals("d" + i, d.diverged.get(i).source.key[0]);
      assertEquals(List.of(PAYLOAD.get(i)), d.diverged.get(i).columns, "column " + i);
    }
    assertEquals(9, d.driftRows());
    assertTrue(d.conflictsInTarget.isEmpty());
    assertTrue(d.conflictsInSource.isEmpty());

    JsonNode table = r.report.get("tables").get(0);
    assertEquals("article", r.report.get("domain").asText());
    assertEquals("articles", table.get("table").asText());
    assertEquals("missing", table.get("missingInService").get(0).get("id").asText());
    assertEquals("extra", table.get("extraInService").get(0).get("id").asText());
    assertEquals(7, table.get("divergedTotal").asInt());
    assertEquals("created_at", table.get("diverged").get(5).get("columns").get(0).asText());
    assertEquals(0, table.get("uniqueConflictsInServiceTotal").asInt());
    assertEquals(0, table.get("uniqueConflictsInMonolithTotal").asInt());
    assertEquals("DRIFT", r.report.get("summary").get("status").asText());
    assertEquals(9, r.report.get("summary").get("driftRows").asInt());
  }

  @Test
  void reconcileTimestampsCompareAsStoredNotAsInstants() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insertRaw(src, "a1", "u", "s", "T", "d", "b", 1704067200000L, 1704067200000L);
    ArticleTestDb.insertRaw(
        dst, "a1", "u", "s", "T", "d", "b", "2024-01-01 00:00:00", "2024-01-01 00:00:00");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(1, r.before.diverged.size());
    assertEquals(List.of("created_at", "updated_at"), r.before.diverged.get(0).columns);
  }

  @Test
  void reconcileEmptyTablesIsClean() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.before.driftRows());
    assertEquals(r.before.sourceChecksum, r.before.targetChecksum);
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());
    assertEquals(1, r.report.get("summary").get("clean").asInt());
  }

  @Test
  void repairToTargetConvergesInOnePass() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "kept");
    ArticleTestDb.insert(dst, "kept");
    ArticleTestDb.insert(src, "missing");
    ArticleTestDb.insert(src, "diverged");
    ArticleTestDb.insert(dst, "diverged", "u", "slug-diverged", "Service edit", "d", "b");
    ArticleTestDb.insert(dst, "extra");
    TagTestDb.insertArticleWithTags(dst, "extra", "orphan");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    o.report = dir.resolve("r.json");
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(3, r.before.driftRows());
    assertEquals(1, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(1, r.deleted);
    assertEquals(0, r.after.driftRows());
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
    assertEquals(1, TagTestDb.countPairs(dst), "article_tags of the deleted extra are left alone");

    JsonNode report = new ObjectMapper().readTree(Files.readAllBytes(o.report));
    assertEquals("to-target", report.get("repair").get("mode").asText());
    assertEquals(0, report.get("repair").get("divergedAfter").asInt());
    assertEquals("CLEAN", report.get("summary").get("status").asText());
  }

  @Test
  void repairToTargetWithoutDeleteExtrasKeepsServiceOnlyRows() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "missing");
    ArticleTestDb.insert(dst, "extra");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(1, r.inserted);
    assertEquals(0, r.deleted);
    assertEquals(1, r.after.driftRows(), "the extra remains and is reported");
    assertEquals(List.of("extra", "missing"), ArticleTestDb.ids(dst));
  }

  @Test
  void repairToSourceConvergesInOnePass() throws Exception {
    Path mono = ArticleTestDb.create(dir, "dev.db");
    Path svc = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(svc, "new-in-service");
    ArticleTestDb.insert(svc, "edited");
    ArticleTestDb.insert(mono, "edited", "u", "slug-edited", "Stale monolith copy", "d", "b");
    ArticleTestDb.insert(mono, "deleted-in-service");

    Reconcile.Options o = opts(mono, svc);
    o.repair = Reconcile.Repair.TO_SOURCE;
    o.deleteExtras = true;
    o.authoritative = "service";
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(1, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(1, r.deleted);
    assertEquals(0, r.after.driftRows());
    assertEquals(ArticleTestDb.rows(svc), ArticleTestDb.rows(mono));
    assertEquals("service", r.report.get("authoritative").asText());
  }

  @Test
  void repairMaxRepairAbortsBeforeWriting() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    for (int i = 0; i < 5; i++) {
      ArticleTestDb.insert(src, "m" + i);
    }

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.maxRepair = 3;
    assertThrows(SyncException.class, () -> new Reconcile(o, out).run());
    assertEquals(0, ArticleTestDb.count(dst));
  }

  // ---------------------------------------------------------------- slug conflicts

  @Test
  void reconcileReportsSlugHeldByAnotherIdAsConflictAndDoesNotCrashOnRepair() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1", "u", "clash", "T", "d", "b");
    ArticleTestDb.insert(dst, "svc-9", "u", "clash", "T", "d", "b");
    ArticleTestDb.insert(src, "ok");

    Reconcile.Outcome report = new Reconcile(opts(src, dst), out).run();
    assertEquals(2, report.before.missingInTarget.size());
    assertEquals(1, report.before.extraInTarget.size());
    assertEquals(1, report.before.conflictsInTarget.size());
    Conflict c = report.before.conflictsInTarget.get(0);
    assertEquals("a1", c.row.key[0]);
    assertEquals("slug", c.column);
    assertEquals("clash", c.value);
    assertEquals("svc-9", c.conflictingKey);
    assertEquals(1, report.before.conflictsInSource.size());
    assertEquals("svc-9", report.before.conflictsInSource.get(0).row.key[0]);
    assertEquals("a1", report.before.conflictsInSource.get(0).conflictingKey);
    JsonNode table = report.report.get("tables").get(0);
    JsonNode conflict = table.get("uniqueConflictsInService").get(0);
    assertEquals("a1", conflict.get("id").asText());
    assertEquals("slug", conflict.get("column").asText());
    assertEquals("clash", conflict.get("value").asText());
    assertEquals("svc-9", conflict.get("conflictingId").asText());
    assertEquals(1, table.get("uniqueConflictsInMonolithTotal").asInt());

    // repair without --delete-extras: the blocked row is skipped, the rest converges, drift stays
    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome partial = new Reconcile(o, out).run();
    assertEquals(1, partial.inserted, "only 'ok' is inserted");
    assertEquals(List.of("ok", "svc-9"), ArticleTestDb.ids(dst));
    assertEquals(2, partial.after.driftRows(), "a1 still missing + svc-9 still extra");
    assertEquals(1, partial.after.conflictsInTarget.size());
    assertTrue(log.toString().contains("reconcile table=articles skipped=1 rows"));

    // repair with --delete-extras: the holder is deleted first, so the insert lands
    o.deleteExtras = true;
    Reconcile.Outcome full = new Reconcile(o, out).run();
    assertEquals(1, full.deleted);
    assertEquals(1, full.inserted);
    assertEquals(0, full.after.driftRows());
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
  }

  @Test
  void reconcileSlugSwapBetweenTwoIdsIsBlockedUntilResolvedByHand() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1", "u", "one", "T", "d", "b");
    ArticleTestDb.insert(src, "a2", "u", "two", "T", "d", "b");
    ArticleTestDb.insert(dst, "a1", "u", "two", "T", "d", "b");
    ArticleTestDb.insert(dst, "a2", "u", "one", "T", "d", "b");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(2, r.before.diverged.size());
    assertEquals(2, r.before.conflictsInTarget.size());
    assertEquals(0, r.updated, "neither update can be applied without violating slug UNIQUE");
    assertEquals(2, r.after.driftRows());
    assertEquals("two", ArticleTestDb.slugOf(dst, "a1"));
    assertEquals("one", ArticleTestDb.slugOf(dst, "a2"));
  }

  @Test
  void reconcileDivergedSlugMovingToAFreeValueIsRepaired() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1", "u", "new-title", "New title", "d", "b");
    ArticleTestDb.insert(dst, "a1", "u", "old-title", "Old title", "d", "b");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(List.of("slug", "title"), r.before.diverged.get(0).columns);
    assertTrue(r.before.conflictsInTarget.isEmpty());
    assertEquals(1, r.updated);
    assertEquals(0, r.after.driftRows());
    assertEquals("new-title", ArticleTestDb.slugOf(dst, "a1"));
  }

  // ---------------------------------------------------------------- CLI

  @Test
  void cliBackfillReconcileRepairAndReportRoundTrip() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insert(src, "a1");
    ArticleTestDb.insert(src, "a2");
    Path report = dir.resolve("reports/article.json");

    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--chunk",
            "1"));
    ArticleTestDb.update(dst, "a2", "body", "drifted");
    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--report",
            report.toString()));
    JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(report));
    assertEquals("article", json.get("domain").asText());
    assertEquals("DRIFT", json.get("summary").get("status").asText());
    assertEquals(
        "body", json.get("tables").get(0).get("diverged").get(0).get("columns").get(0).asText());

    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--repair",
            "to-target",
            "--delete-extras",
            "--max-repair",
            "10"));
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "article",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
  }

  @Test
  void cliHelpMentionsArticle() {
    assertEquals(Main.EXIT_OK, run("--help"));
    assertTrue(log.toString().contains("article"));
    assertTrue(log.toString().contains("slug"));
  }

  // ---------------------------------------------------------------- scale

  @Test
  void tenThousandRowsBackfillAndReconcileCleanly() throws Exception {
    Path src = ArticleTestDb.create(dir, "dev.db");
    Path dst = ArticleTestDb.create(dir, "article.db");
    ArticleTestDb.insertMany(src, 10_000);

    Backfill.Result b = backfill(src, dst, 1000).run();
    assertEquals(10_000, b.rowsRead);
    assertEquals(10_000, b.rowsInserted);
    assertEquals(0, b.conflicts);
    assertEquals(10_000, ArticleTestDb.count(dst));

    Reconcile.Outcome clean = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, clean.before.driftRows());
    assertEquals(clean.before.sourceChecksum, clean.before.targetChecksum);

    ArticleTestDb.update(dst, "article-004242", "title", "drift");
    ArticleTestDb.delete(dst, "article-000007");
    ArticleTestDb.insert(dst, "zz-extra");
    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(3, r.before.driftRows());
    assertEquals(0, r.after.driftRows());
    assertEquals(ArticleTestDb.rows(src), ArticleTestDb.rows(dst));
    assertFalse(r.report.get("tables").get(0).has("truncated"));
  }
}
