package io.spring.tools.favoritesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconcileTest {

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);

  private Reconcile.Options opts(Path src, Path dst) {
    Reconcile.Options o = new Reconcile.Options();
    o.source = src;
    o.target = dst;
    return o;
  }

  @Test
  void detectsMissingAndExtraRowsAndWritesReport() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "article-1|user-1", "article-2|user-2", "article-3|user-7");
    TestDb.insert(dst, "article-1|user-1", "article-2|user-2", "article-9|user-9");

    Reconcile.Options o = opts(src, dst);
    o.report = dir.resolve("reports/out.json");
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(2, r.before.driftRows());
    assertEquals("article-3|user-7", r.before.missingInTarget.get(0).toString());
    assertEquals("article-9|user-9", r.before.extraInTarget.get(0).toString());
    assertNull(r.after);
    assertNotEquals(r.before.sourceChecksum, r.before.targetChecksum);

    JsonNode json = new ObjectMapper().readTree(Files.readAllBytes(o.report));
    assertEquals("favorite", json.get("domain").asText());
    assertEquals("monolith", json.get("authoritative").asText());
    assertEquals(0, json.get("graceSeconds").asInt());
    assertTrue(json.get("runId").asText().endsWith("Z"));
    JsonNode table = json.get("tables").get(0);
    assertEquals("article_favorites", table.get("table").asText());
    assertEquals(3, table.get("monolithCount").asLong());
    assertEquals(3, table.get("serviceCount").asLong());
    assertEquals("article-3", table.get("missingInService").get(0).get("articleId").asText());
    assertEquals("user-7", table.get("missingInService").get(0).get("userId").asText());
    assertEquals("article-9", table.get("extraInService").get(0).get("articleId").asText());
    assertEquals(0, table.get("diverged").size());
    assertEquals("DRIFT", table.get("status").asText());
    assertEquals(64, table.get("monolithChecksum").asText().length());
    assertEquals(1, json.get("summary").get("drift").asInt());
    assertEquals(0, json.get("summary").get("clean").asInt());
    assertEquals("DRIFT", json.get("summary").get("status").asText());
    assertNull(json.get("repair"));
    assertTrue(log.toString().contains("missing=1 extra=1 diverged=0 status=DRIFT"));
  }

  @Test
  void identicalTablesAreClean() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|u", "b|u");
    TestDb.insert(dst, "b|u", "a|u");
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift());
    assertEquals(r.before.sourceChecksum, r.before.targetChecksum);
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());
    assertEquals(1, r.report.get("summary").get("clean").asInt());
  }

  @Test
  void emptyTablesAreClean() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift());
    assertEquals(0, r.before.sourceCount);
    assertEquals(r.before.sourceChecksum, r.before.targetChecksum);
  }

  @Test
  void repairToTargetInsertsMissingButKeepsExtrasByDefault() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "b|2", "c|3");
    TestDb.insert(dst, "a|1", "z|9");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(2, r.inserted);
    assertEquals(0, r.deleted);
    assertEquals(Set.of("a|1", "b|2", "c|3", "z|9"), TestDb.keys(dst));
    assertEquals(Set.of("a|1", "b|2", "c|3"), TestDb.keys(src), "source untouched");
    assertEquals(1, r.remainingDrift(), "the extra row is still reported");
    assertEquals("to-target", r.report.get("repair").get("mode").asText());
    assertEquals(0, r.report.get("repair").get("missingInServiceAfter").asInt());
    assertEquals(1, r.report.get("repair").get("extraInServiceAfter").asInt());
  }

  @Test
  void repairToTargetWithDeleteExtrasConvergesToZeroDrift() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "b|2", "c|3");
    TestDb.insert(dst, "a|1", "z|9");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(2, r.inserted);
    assertEquals(1, r.deleted);
    assertEquals(0, r.remainingDrift());
    assertEquals(TestDb.keys(src), TestDb.keys(dst));
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());

    Reconcile.Outcome again = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, again.remainingDrift());
  }

  @Test
  void repairToSourceCopiesServiceOnlyRowsIntoMonolith() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "only-monolith|1");
    TestDb.insert(dst, "a|1", "only-service|2");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_SOURCE;
    o.authoritative = "service";
    Reconcile.Outcome r = new Reconcile(o, out).run();

    assertEquals(1, r.inserted);
    assertEquals(Set.of("a|1", "only-monolith|1", "only-service|2"), TestDb.keys(src));
    assertEquals(Set.of("a|1", "only-service|2"), TestDb.keys(dst), "service untouched");
    assertEquals("service", r.report.get("authoritative").asText());
    assertEquals(0, r.after.extraInTarget.size());
    assertEquals(
        1, r.after.missingInTarget.size(), "monolith-only row kept without --delete-extras");
  }

  @Test
  void repairAbortsAboveMaxRepair() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insert(src, "a|1", "b|2", "c|3");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.maxRepair = 2;
    SyncException e = assertThrows(SyncException.class, () -> new Reconcile(o, out).run());
    assertTrue(e.getMessage().contains("--max-repair"), e.getMessage());
    assertEquals(0, TestDb.count(dst), "nothing written when the guard trips");
  }

  @Test
  void defaultMaxRepairIsOnePercentButAtLeastOneThousand() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    Reconcile r = new Reconcile(opts(src, dst), out);
    assertEquals(1000, r.effectiveMaxRepair(10));
    assertEquals(1000, r.effectiveMaxRepair(100_000));
    assertEquals(5000, r.effectiveMaxRepair(500_000));
  }

  @Test
  void truncatesKeyListsAtOneThousand() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insertMany(src, 12, 100); // 1200 missing rows

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    JsonNode table = r.report.get("tables").get(0);
    assertEquals(1000, table.get("missingInService").size());
    assertEquals(1200, table.get("missingInServiceTotal").asInt());
    assertTrue(table.get("truncated").asBoolean());
    assertEquals(1200, r.before.missingInTarget.size(), "repair still sees every key");
  }

  @Test
  void tenThousandRowsReconcileQuickly() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.create(dir, "favorite.db");
    TestDb.insertMany(src, 100, 100);
    TestDb.insertMany(dst, 100, 100);
    TestDb.delete(dst, "article-000050|user-000050");

    long start = System.nanoTime();
    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(1, r.remainingDrift());
    assertEquals("article-000050|user-000050", r.before.missingInTarget.get(0).toString());
    assertFalse(r.report.get("tables").get(0).has("truncated"));
    assertTrue(millis < 30_000, "reconcile of 10k rows took " + millis + " ms");
  }

  @Test
  void failsClearlyWhenTableMissing() throws Exception {
    Path src = TestDb.create(dir, "dev.db");
    Path dst = TestDb.createEmptyFile(dir, "favorite.db");
    SyncException e =
        assertThrows(SyncException.class, () -> new Reconcile(opts(src, dst), out).run());
    assertTrue(e.getMessage().contains("favorite.db"), e.getMessage());
  }
}
