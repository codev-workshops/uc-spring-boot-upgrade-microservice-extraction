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
 * {@code --domain tag}: {@code tags} and {@code article_tags} are backfilled and reconciled in one
 * run, tags first, and duplicate {@code article_tags} pairs are reported rather than multiplied.
 */
class TagSyncTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errLog = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);
  private final PrintStream err = new PrintStream(errLog, true);

  private Reconcile.Options opts(Path src, Path dst) {
    Reconcile.Options o = new Reconcile.Options();
    o.domain = Domain.TAG;
    o.source = src;
    o.target = dst;
    return o;
  }

  private Backfill backfill(Path src, Path dst, int chunk) {
    return new Backfill(Domain.TAG, src, dst, chunk, out);
  }

  private int run(String... args) {
    return Main.run(args, out, err);
  }

  // ---------------------------------------------------------------- backfill

  @Test
  void backfillCopiesTagsBeforePairsAndIsIdempotent() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    TagTestDb.insertArticleWithTags(src, "article-2", "java");

    Backfill.Result first = backfill(src, dst, 2).run();
    assertEquals(5, first.rowsRead, "2 tags + 3 pairs");
    assertEquals(5, first.rowsInserted);
    assertEquals(0, first.rowsSkipped);
    assertEquals(List.of("tags", "article_tags"), tableNames(first));
    assertEquals(2, first.tables.get(0).rowsInserted);
    assertEquals(3, first.tables.get(1).rowsInserted);
    assertEquals(TagTestDb.tags(src), TagTestDb.tags(dst));
    assertEquals(TagTestDb.pairs(src), TagTestDb.pairs(dst));
    assertTrue(
        log.toString().indexOf("table=tags") < log.toString().indexOf("table=article_tags"),
        "tags are copied before the relations that reference them");
    assertTrue(log.toString().contains("backfill T0="));

    Backfill.Result second = backfill(src, dst, 2).run();
    assertEquals(5, second.rowsRead);
    assertEquals(0, second.rowsInserted);
    assertEquals(5, second.rowsSkipped);
    assertEquals(TagTestDb.tags(src), TagTestDb.tags(dst));
    assertEquals(TagTestDb.pairs(src), TagTestDb.pairs(dst));
  }

  @Test
  void backfillRestartAfterCrashMidRunConverges() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertMany(src, 100, 2); // chunk 30: 4 chunks of tags, then 7 of pairs

    SQLException boom = new SQLException("simulated crash after chunk 5");
    Backfill crashing =
        new Backfill(
            Domain.TAG,
            src,
            dst,
            30,
            out,
            (i, n) -> {
              if (i == 5) {
                throw boom;
              }
            });
    assertEquals(boom, assertThrows(SQLException.class, crashing::run));
    assertEquals(100, TagTestDb.countTags(dst), "tags finished before the crash");
    assertEquals(30, TagTestDb.countPairs(dst), "one committed pair chunk survives");

    Backfill.Result resumed = backfill(src, dst, 30).run();
    assertEquals(300, resumed.rowsRead);
    assertEquals(170, resumed.rowsInserted);
    assertEquals(130, resumed.rowsSkipped);
    assertEquals(TagTestDb.tags(src), TagTestDb.tags(dst));
    assertEquals(sorted(TagTestDb.pairs(src)), sorted(TagTestDb.pairs(dst)));
  }

  @Test
  void backfillKeepsRowsAlreadyPresentInTargetAndServiceOnlyRows() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java");
    TagTestDb.insertTag(dst, "tag-java", "renamed-in-service");
    TagTestDb.insertPair(dst, "article-1", "tag-java");
    TagTestDb.insertTag(dst, "tag-service-only", "service-only");
    TagTestDb.insertPair(dst, "article-9", "tag-service-only");

    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsInserted);
    assertEquals(2, r.rowsSkipped);
    assertEquals("renamed-in-service", TagTestDb.tags(dst).get("tag-java"));
    assertEquals(
        List.of("tag-java", "tag-service-only"), List.copyOf(TagTestDb.tags(dst).keySet()));
    assertEquals(List.of("article-1|tag-java", "article-9|tag-service-only"), TagTestDb.pairs(dst));
  }

  @Test
  void backfillEmptyTablesIsANoOp() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsRead);
    assertEquals(0, r.rowsInserted);
    assertEquals(0, r.chunks);
    assertEquals(2, r.tables.size());
    assertEquals(0, TagTestDb.countTags(dst));
    assertEquals(0, TagTestDb.countPairs(dst));
  }

  @Test
  void backfillDoesNotMultiplyDuplicatePairRowsFromTheSource() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertTag(src, "tag-java", "java");
    TagTestDb.insertPair(src, "article-1", "tag-java");
    TagTestDb.insertPair(src, "article-1", "tag-java");
    TagTestDb.insertPair(src, "article-2", "tag-java");

    Backfill.Result r = backfill(src, dst, 5000).run();
    Backfill.TableResult pairs = r.tables.get(1);
    assertEquals(3, pairs.rowsRead, "all three source rows are walked");
    assertEquals(2, pairs.rowsInserted, "only the two distinct pairs are inserted");
    assertEquals(1, pairs.rowsSkipped, "the repeated pair is skipped");
    assertEquals(
        List.of("article-1|tag-java", "article-2|tag-java"),
        TagTestDb.pairs(dst),
        "the duplicated pair is stored once");

    backfill(src, dst, 5000).run();
    assertEquals(2, TagTestDb.countPairs(dst), "a second run still does not multiply it");
  }

  @Test
  void backfillFailsClearlyWhenATargetTableIsMissing() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db", true, false);
    SyncException e = assertThrows(SyncException.class, () -> backfill(src, dst, 10).run());
    assertTrue(e.getMessage().contains("article_tags"), e.getMessage());
    assertTrue(e.getMessage().contains("Flyway"), e.getMessage());
  }

  // --------------------------------------------------------------- reconcile

  @Test
  void reconcileReportsDriftPerTable() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    TagTestDb.insertTag(dst, "tag-java", "java");
    TagTestDb.insertTag(dst, "tag-extra", "extra");
    TagTestDb.insertPair(dst, "article-1", "tag-java");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();

    assertEquals(3, r.remainingDrift());
    assertEquals(1, r.before("tags").missingInTarget.size());
    assertEquals(1, r.before("tags").extraInTarget.size());
    assertEquals(1, r.before("article_tags").missingInTarget.size());
    assertEquals(
        "article-1|tag-spring", r.before("article_tags").missingInTarget.get(0).toString());

    JsonNode tables = r.report.get("tables");
    assertEquals("tag", r.report.get("domain").asText());
    assertEquals("tags", tables.get(0).get("table").asText());
    assertEquals("article_tags", tables.get(1).get("table").asText());
    assertEquals("DRIFT", tables.get(0).get("status").asText());
    assertEquals("DRIFT", tables.get(1).get("status").asText());
    assertEquals("articleId", tables.get(1).get("missingInService").get(0).fieldNames().next());
    assertEquals(2, r.report.get("summary").get("drift").asInt());
    assertEquals(3, r.report.get("summary").get("driftRows").asInt());
    assertTrue(
        log.toString().contains("reconcile domain=tag table=article_tags phase=before"),
        log.toString());
  }

  @Test
  void reconcileFlagsDivergedTagName() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertTag(src, "tag-1", "java");
    TagTestDb.insertTag(dst, "tag-1", "Java");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(1, r.before("tags").diverged.size());
    assertEquals(List.of("name"), r.before("tags").diverged.get(0).columns.subList(0, 1));
    assertEquals(0, r.before("article_tags").driftRows());
    assertEquals("DRIFT", r.report.get("summary").get("status").asText());
  }

  @Test
  void reconcileIdenticalAndEmptyTablesAreClean() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    Reconcile.Outcome empty = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, empty.remainingDrift());
    assertEquals("CLEAN", empty.report.get("summary").get("status").asText());
    assertEquals(2, empty.report.get("summary").get("clean").asInt());

    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    TagTestDb.insertArticleWithTags(dst, "article-1", "java", "spring");
    Reconcile.Outcome same = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, same.remainingDrift());
    assertEquals(same.before("tags").sourceChecksum, same.before("tags").targetChecksum);
    assertEquals(
        same.before("article_tags").sourceChecksum, same.before("article_tags").targetChecksum);
  }

  @Test
  void reconcileReportsDuplicatePairsWithoutTreatingThemAsDrift() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertTag(src, "tag-java", "java");
    TagTestDb.insertTag(dst, "tag-java", "java");
    TagTestDb.insertPair(src, "article-1", "tag-java");
    TagTestDb.insertPair(src, "article-1", "tag-java");
    TagTestDb.insertPair(dst, "article-1", "tag-java");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift(), "a duplicate row is not missing data");
    assertEquals(1, r.before("article_tags").duplicateInSource.size());
    assertEquals(0, r.before("article_tags").duplicateInTarget.size());
    JsonNode pairs = r.report.get("tables").get(1);
    assertEquals(1, pairs.get("duplicateKeysInMonolithTotal").asInt());
    assertEquals(
        "article-1", pairs.get("duplicateKeysInMonolith").get(0).get("articleId").asText());
    assertEquals(0, pairs.get("duplicateKeysInServiceTotal").asInt());
    assertFalse(r.report.get("tables").get(0).has("duplicateKeysInMonolith"), "tags.id is a PK");
    assertTrue(log.toString().contains("duplicateKeys=1/0"));
  }

  @Test
  void repairToTargetConvergesBothTables() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    TagTestDb.insertArticleWithTags(src, "article-2", "java");
    TagTestDb.insertTag(dst, "tag-java", "tampered");
    TagTestDb.insertTag(dst, "tag-gone", "gone");
    TagTestDb.insertPair(dst, "article-1", "tag-java");
    TagTestDb.insertPair(dst, "article-7", "tag-gone");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome kept = new Reconcile(o, out).run();
    assertEquals(3, kept.inserted, "1 tag + 2 pairs");
    assertEquals(1, kept.updated);
    assertEquals(0, kept.deleted);
    assertEquals(2, kept.remainingDrift(), "the two extras are still reported");
    assertTrue(log.toString().contains("extras kept; pass --delete-extras"));

    o.deleteExtras = true;
    Reconcile.Outcome swept = new Reconcile(o, out).run();
    assertEquals(2, swept.deleted);
    assertEquals(0, swept.remainingDrift());
    assertEquals(TagTestDb.tags(src), TagTestDb.tags(dst));
    assertEquals(sorted(TagTestDb.pairs(src)), sorted(TagTestDb.pairs(dst)));
    assertEquals(0, new Reconcile(opts(src, dst), out).run().remainingDrift());
  }

  @Test
  void repairToSourceCopiesServiceRowsIntoTheMonolith() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertTag(src, "tag-java", "java");
    TagTestDb.insertPair(src, "article-1", "tag-java");
    TagTestDb.insertTag(dst, "tag-java", "java (authoritative)");
    TagTestDb.insertPair(dst, "article-1", "tag-java");
    TagTestDb.insertTag(dst, "tag-new", "written-in-service");
    TagTestDb.insertPair(dst, "article-5", "tag-new");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_SOURCE;
    o.authoritative = "service";
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(2, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(0, r.remainingDrift());
    assertEquals(TagTestDb.tags(dst), TagTestDb.tags(src));
    assertEquals("service", r.report.get("authoritative").asText());
    assertTrue(TagTestDb.pairs(src).contains("article-5|tag-new"));
  }

  @Test
  void repairAbortsAboveMaxRepairCountingBothTables() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    TagTestDb.insertArticleWithTags(src, "article-2", "kotlin");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.maxRepair = 5;
    SyncException e = assertThrows(SyncException.class, () -> new Reconcile(o, out).run());
    assertTrue(e.getMessage().contains("repair would touch 6 rows"), e.getMessage());
    assertEquals(0, TagTestDb.countTags(dst), "no table is touched when the guard trips");
    assertEquals(0, TagTestDb.countPairs(dst));
  }

  @Test
  void tenThousandRowsSanity() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertMany(src, 5_000, 1); // 5k tags + 5k pairs

    long start = System.nanoTime();
    Backfill.Result b = backfill(src, dst, 1_000).run();
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(10_000, b.rowsInserted);
    assertEquals(0, r.remainingDrift());
    assertFalse(r.report.get("tables").get(0).has("truncated"));
    assertTrue(millis < 60_000, "backfill + reconcile of 10k tag rows took " + millis + " ms");
  }

  // --------------------------------------------------------------------- CLI

  @Test
  void cliBackfillReconcileAndReverseBackfillRoundTrip() throws Exception {
    Path src = TagTestDb.create(dir, "dev.db");
    Path dst = TagTestDb.create(dir, "article.db");
    TagTestDb.insertArticleWithTags(src, "article-1", "java", "spring");
    Path report = dir.resolve("out.json");

    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain=tag",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--chunk",
            "1"));
    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "tag",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--report",
            report.toString()));
    JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(report));
    assertEquals("tag", json.get("domain").asText());
    assertEquals("tags", json.get("tables").get(0).get("table").asText());
    assertEquals("article_tags", json.get("tables").get(1).get("table").asText());

    // state C: the service owns the writes, then roll back into the monolith
    TagTestDb.insertTag(dst, "tag-scala", "scala");
    TagTestDb.insertPair(dst, "article-3", "tag-scala");
    TagTestDb.deletePairs(dst, "tag-spring");
    TagTestDb.deleteTag(dst, "tag-spring");
    assertEquals(
        Main.EXIT_OK,
        run(
            "reverse-backfill",
            "--domain",
            "tag",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    assertTrue(log.toString().contains("reverse-backfill: copying"));
    assertEquals(List.of("java", "spring", "scala"), TagTestDb.tagNames(src));
    assertTrue(TagTestDb.pairs(src).contains("article-3|tag-scala"));
    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--domain",
            "tag",
            "--source",
            src.toString(),
            "--target",
            dst.toString(),
            "--authoritative",
            "service"),
        "the tag deleted in state C is reported as missing in the service");
  }

  private static List<String> sorted(List<String> values) {
    List<String> copy = new java.util.ArrayList<>(values);
    java.util.Collections.sort(copy);
    return copy;
  }

  private static List<String> tableNames(Backfill.Result r) {
    List<String> names = new java.util.ArrayList<>();
    for (Backfill.TableResult t : r.tables) {
      names.add(t.table);
    }
    return names;
  }
}
