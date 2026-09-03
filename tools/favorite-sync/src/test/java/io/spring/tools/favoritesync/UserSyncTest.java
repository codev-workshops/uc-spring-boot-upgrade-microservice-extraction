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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --domain user}: {@code users} (keyed by id, five payload columns, {@code username} and
 * {@code email} UNIQUE) and {@code follows} (bare pair, no constraint) are backfilled and
 * reconciled in one run, users first. The password hash is compared as stored and never appears in
 * any report or log line.
 */
class UserSyncTest {

  private static final List<String> PAYLOAD =
      List.of("username", "password", "email", "bio", "image");

  @TempDir Path dir;

  private final ByteArrayOutputStream log = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errLog = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(log, true);
  private final PrintStream err = new PrintStream(errLog, true);

  private Reconcile.Options opts(Path src, Path dst) {
    Reconcile.Options o = new Reconcile.Options();
    o.domain = Domain.USER;
    o.source = src;
    o.target = dst;
    return o;
  }

  private Backfill backfill(Path src, Path dst, int chunk) {
    return new Backfill(Domain.USER, src, dst, chunk, out);
  }

  private int run(String... args) {
    return Main.run(args, out, err);
  }

  private static List<String> tableNames(Backfill.Result r) {
    return r.tables.stream().map(t -> t.table).collect(Collectors.toList());
  }

  /** Every hash the fixtures could have written for these ids. */
  private static void assertNoHashLeaks(String text, String... ids) {
    for (String id : ids) {
      assertFalse(text.contains(UserTestDb.hash(id)), "hash of " + id + " leaked");
    }
    assertFalse(text.contains("$2a$"), "a bcrypt-shaped value leaked");
  }

  // ---------------------------------------------------------------- domain

  @Test
  void domainCoversUsersThenFollows() {
    assertEquals(Domain.USER, Domain.parse("user"));
    assertEquals(List.of("users", "follows"), Domain.USER.tableNames());
    SyncTable users = Domain.USER.tables.get(0);
    assertEquals(List.of("id"), users.keyColumns);
    assertEquals(PAYLOAD, users.payloadColumns);
    assertEquals(List.of("username", "email"), users.uniqueColumns);
    assertEquals(List.of("password"), users.sensitiveColumns);
    assertTrue(users.uniqueKey);
    SyncTable follows = Domain.USER.tables.get(1);
    assertEquals(List.of("user_id", "follow_id"), follows.keyColumns);
    assertFalse(follows.uniqueKey);
    assertFalse(follows.hasPayload());
  }

  // ---------------------------------------------------------------- backfill

  @Test
  void backfillCopiesUsersBeforeFollowsVerbatimAndIsIdempotent() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1");
    UserTestDb.insertUser(src, "u2", "jane", UserTestDb.hash("u2"), "jane@x.io", "Bio\nline", null);
    UserTestDb.insertUser(src, "u3", "joe", UserTestDb.hash("u3"), "joe@x.io", null, "");
    UserTestDb.insertFollow(src, "u1", "u2");
    UserTestDb.insertFollow(src, "u2", "u1");
    UserTestDb.insertFollow(src, "u3", "u1");

    Backfill.Result first = backfill(src, dst, 2).run();
    assertEquals(6, first.rowsRead, "3 users + 3 follows");
    assertEquals(6, first.rowsInserted);
    assertEquals(0, first.rowsSkipped);
    assertEquals(0, first.conflicts);
    assertEquals(List.of("users", "follows"), tableNames(first));
    assertEquals(3, first.table("users").rowsInserted);
    assertEquals(3, first.table("follows").rowsInserted);
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst), "all columns incl. hash + nulls");
    assertEquals(UserTestDb.follows(src), UserTestDb.follows(dst));
    assertTrue(
        log.toString().indexOf("table=users") < log.toString().indexOf("table=follows"),
        "users are copied before the relations that reference them");
    assertTrue(log.toString().contains("backfill T0="));
    assertNoHashLeaks(log.toString(), "u1", "u2", "u3");

    Backfill.Result second = backfill(src, dst, 2).run();
    assertEquals(6, second.rowsRead);
    assertEquals(0, second.rowsInserted);
    assertEquals(6, second.rowsSkipped);
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst));
    assertEquals(UserTestDb.follows(src), UserTestDb.follows(dst), "follows not multiplied");
  }

  @Test
  void backfillRestartAfterCrashMidRunConverges() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertMany(src, 100, 2);

    SQLException boom = new SQLException("simulated crash after chunk 5");
    Backfill crashing =
        new Backfill(
            Domain.USER,
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
    assertEquals(100, UserTestDb.countUsers(dst), "users finished (4 chunks)");
    assertEquals(30, UserTestDb.countFollows(dst), "one committed follows chunk survives");

    Backfill.Result resumed = backfill(src, dst, 30).run();
    assertEquals(300, resumed.rowsRead);
    assertEquals(170, resumed.rowsInserted);
    assertEquals(130, resumed.rowsSkipped);
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst));
    assertEquals(UserTestDb.follows(src), UserTestDb.follows(dst));
  }

  @Test
  void backfillKeepsRowsAlreadyPresentInTargetAndServiceOnlyRows() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1");
    UserTestDb.insertUser(
        dst, "u1", "name-u1", UserTestDb.hash("u1"), "u1@example.com", "edited in service", "");
    UserTestDb.insertUser(dst, "service-only");
    UserTestDb.insertFollow(dst, "service-only", "u1");

    Backfill.Result r = backfill(src, dst, 5000).run();
    assertEquals(0, r.rowsInserted);
    assertEquals(1, r.rowsSkipped);
    assertEquals(0, r.conflicts);
    assertEquals(List.of("service-only", "u1"), UserTestDb.userIds(dst));
    assertEquals("edited in service", UserTestDb.columnOf(dst, "u1", "bio"));
    assertEquals(List.of("service-only>u1"), UserTestDb.follows(dst));
  }

  @Test
  void backfillEmptyTablesIsANoOp() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(0, r.rowsRead);
    assertEquals(0, r.rowsInserted);
    assertEquals(2, r.tables.size());
    assertEquals(0, UserTestDb.countUsers(dst));
    assertEquals(0, UserTestDb.countFollows(dst));
  }

  @Test
  void backfillMissingFollowsTableIsAnError() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db", true, false);
    UserTestDb.insertUser(src, "u1");

    SyncException e = assertThrows(SyncException.class, () -> backfill(src, dst, 100).run());
    assertTrue(e.getMessage().contains("follows"), e.getMessage());
    assertEquals(0, UserTestDb.countUsers(dst), "nothing is written when a table is missing");
  }

  @Test
  void backfillDoesNotMultiplyDuplicateFollowPairsFromTheSource() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1");
    UserTestDb.insertUser(src, "u2");
    UserTestDb.insertFollow(src, "u1", "u2");
    UserTestDb.insertFollow(src, "u1", "u2");
    UserTestDb.insertFollow(src, "u2", "u1");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(5, r.rowsRead);
    assertEquals(4, r.rowsInserted);
    assertEquals(1, r.rowsSkipped);
    assertEquals(List.of("u1>u2", "u2>u1"), UserTestDb.follows(dst), "pair stored once");

    backfill(src, dst, 100).run();
    assertEquals(List.of("u1>u2", "u2>u1"), UserTestDb.follows(dst), "re-run adds nothing");
  }

  @Test
  void backfillReportsUsernameAndEmailClashesAsConflictsInsteadOfCrashing() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "jane", UserTestDb.hash("u1"), "jane@x.io", "", "");
    UserTestDb.insertUser(src, "u2", "joe", UserTestDb.hash("u2"), "joe@x.io", "", "");
    UserTestDb.insertUser(src, "u3", "ok", UserTestDb.hash("u3"), "ok@x.io", "", "");
    UserTestDb.insertUser(dst, "svc-1", "jane", UserTestDb.hash("svc-1"), "other@x.io", "", "");
    UserTestDb.insertUser(dst, "svc-2", "someone", UserTestDb.hash("svc-2"), "joe@x.io", "", "");
    UserTestDb.insertFollow(src, "u1", "u3");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(4, r.rowsRead);
    assertEquals(2, r.rowsInserted, "the non-clashing user and the follow still land");
    assertEquals(2, r.rowsSkipped);
    assertEquals(2, r.conflicts);
    List<Conflict> conflicts = r.table("users").conflicts;
    assertEquals("u1", conflicts.get(0).row.key[0]);
    assertEquals("username", conflicts.get(0).column);
    assertEquals("jane", conflicts.get(0).value);
    assertEquals("svc-1", conflicts.get(0).conflictingKey);
    assertEquals("u2", conflicts.get(1).row.key[0]);
    assertEquals("email", conflicts.get(1).column);
    assertEquals("joe@x.io", conflicts.get(1).value);
    assertEquals("svc-2", conflicts.get(1).conflictingKey);
    assertEquals(List.of("svc-1", "svc-2", "u3"), UserTestDb.userIds(dst));
    assertEquals(List.of("u1>u3"), UserTestDb.follows(dst), "follows are copied regardless");
    assertTrue(log.toString().contains("backfill conflict table=users u1 username=jane"));
    assertTrue(log.toString().contains("backfill conflict table=users u2 email=joe@x.io"));
    assertNoHashLeaks(log.toString(), "u1", "u2", "u3", "svc-1", "svc-2");
  }

  @Test
  void backfillRowClashingOnBothColumnsWithDifferentHoldersReportsBoth() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "jane", UserTestDb.hash("u1"), "jane@x.io", "", "");
    UserTestDb.insertUser(dst, "svc-1", "jane", UserTestDb.hash("svc-1"), "a@x.io", "", "");
    UserTestDb.insertUser(dst, "svc-2", "b", UserTestDb.hash("svc-2"), "jane@x.io", "", "");

    Backfill.Result r = backfill(src, dst, 100).run();
    assertEquals(2, r.conflicts, "one conflict per clashing column");
    assertEquals(
        List.of("username", "email"),
        r.table("users").conflicts.stream().map(c -> c.column).collect(Collectors.toList()));
    assertEquals(
        List.of("svc-1", "svc-2"),
        r.table("users").conflicts.stream()
            .map(c -> c.conflictingKey)
            .collect(Collectors.toList()));
  }

  @Test
  void backfillCliExitsWithDriftWhileAClashRemains() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "same", UserTestDb.hash("u1"), "u1@x.io", "", "");
    UserTestDb.insertUser(dst, "u9", "same", UserTestDb.hash("u9"), "u9@x.io", "", "");

    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "backfill",
            "--domain",
            "user",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    UserTestDb.deleteUser(dst, "u9");
    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain",
            "user",
            "--source",
            src.toString(),
            "--target",
            dst.toString()));
    assertEquals(List.of("u1"), UserTestDb.userIds(dst));
    assertNoHashLeaks(log.toString(), "u1", "u9");
  }

  @Test
  void reverseBackfillCopiesServiceRowsBackIntoTheMonolith() throws Exception {
    Path mono = UserTestDb.create(dir, "dev.db");
    Path svc = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(mono, "u1");
    UserTestDb.insertUser(svc, "u1");
    UserTestDb.insertUser(svc, "registered-after-cutover");
    UserTestDb.insertFollow(svc, "registered-after-cutover", "u1");
    UserTestDb.insertFollow(mono, "u1", "gone");

    int exit =
        run(
            "reverse-backfill",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString());
    assertEquals(Main.EXIT_OK, exit);
    assertEquals(UserTestDb.users(svc), UserTestDb.users(mono));
    assertEquals(
        List.of("registered-after-cutover>u1", "u1>gone"),
        UserTestDb.follows(mono),
        "monolith-only follow is preserved, service follow copied back");
    assertTrue(log.toString().contains("reverse-backfill"));
    assertNoHashLeaks(log.toString(), "u1", "registered-after-cutover");
  }

  // ---------------------------------------------------------------- reconcile

  @Test
  void reconcileReportsMissingExtraAndEveryDivergedColumnWithoutPrintingHashes() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    for (int i = 0; i < PAYLOAD.size(); i++) {
      UserTestDb.insertUser(src, "d" + i);
      UserTestDb.insertUser(dst, "d" + i);
    }
    UserTestDb.updateUser(dst, "d0", "username", "renamed");
    UserTestDb.updateUser(dst, "d1", "password", UserTestDb.hash("changed-password"));
    UserTestDb.updateUser(dst, "d2", "email", "renamed@example.com");
    UserTestDb.updateUser(dst, "d3", "bio", "edited bio");
    UserTestDb.updateUser(dst, "d4", "image", null);
    UserTestDb.insertUser(src, "same");
    UserTestDb.insertUser(dst, "same");
    UserTestDb.insertUser(src, "missing");
    UserTestDb.insertUser(dst, "extra");
    UserTestDb.insertFollow(src, "same", "d0");
    UserTestDb.insertFollow(dst, "same", "d0");
    UserTestDb.insertFollow(src, "missing", "d0");
    UserTestDb.insertFollow(dst, "extra", "d0");

    Reconcile.Options o = opts(src, dst);
    o.report = dir.resolve("r.json");
    Reconcile.Outcome r = new Reconcile(o, out).run();
    Reconcile.Diff users = r.before("users");
    assertEquals(7, users.sourceCount);
    assertEquals(7, users.targetCount);
    assertEquals(List.of("missing"), keys(users.missingInTarget));
    assertEquals(List.of("extra"), keys(users.extraInTarget));
    assertEquals(5, users.diverged.size());
    for (int i = 0; i < PAYLOAD.size(); i++) {
      assertEquals("d" + i, users.diverged.get(i).source.key[0]);
      assertEquals(List.of(PAYLOAD.get(i)), users.diverged.get(i).columns, "column " + i);
    }
    assertEquals(7, users.driftRows());
    assertTrue(users.conflictsInTarget.isEmpty());
    assertTrue(users.conflictsInSource.isEmpty());
    Reconcile.Diff follows = r.before("follows");
    assertEquals(1, follows.missingInTarget.size());
    assertEquals(1, follows.extraInTarget.size());
    assertEquals(2, follows.driftRows());
    assertEquals(9, r.remainingDrift());

    assertEquals("user", r.report.get("domain").asText());
    JsonNode tables = r.report.get("tables");
    assertEquals(2, tables.size());
    assertEquals("users", tables.get(0).get("table").asText());
    assertEquals("follows", tables.get(1).get("table").asText());
    JsonNode u = tables.get(0);
    assertEquals("missing", u.get("missingInService").get(0).get("id").asText());
    assertEquals("extra", u.get("extraInService").get(0).get("id").asText());
    assertEquals(5, u.get("divergedTotal").asInt());
    assertEquals("d1", u.get("diverged").get(1).get("id").asText());
    assertEquals("password", u.get("diverged").get(1).get("columns").get(0).asText());
    assertEquals(1, u.get("diverged").get(1).size() - 1, "a diverged entry carries key + columns");
    assertEquals(0, u.get("uniqueConflictsInServiceTotal").asInt());
    assertFalse(u.has("duplicateKeysInMonolith"), "users has a real primary key");
    JsonNode f = tables.get(1);
    assertEquals("missing", f.get("missingInService").get(0).get("userId").asText());
    assertEquals("d0", f.get("missingInService").get(0).get("followId").asText());
    assertEquals("extra", f.get("extraInService").get(0).get("userId").asText());
    assertFalse(f.has("divergedTotal"), "follows has no payload");
    assertEquals(0, f.get("duplicateKeysInMonolithTotal").asInt());
    assertEquals(2, r.report.get("summary").get("drift").asInt());
    assertEquals(9, r.report.get("summary").get("driftRows").asInt());
    assertEquals("DRIFT", r.report.get("summary").get("status").asText());

    String json = new String(Files.readAllBytes(o.report));
    assertNoHashLeaks(json, "d0", "d1", "d2", "d3", "d4", "same", "missing", "extra");
    assertFalse(json.contains(UserTestDb.hash("changed-password")));
    assertNoHashLeaks(log.toString(), "d0", "d1", "d2", "d3", "d4", "same", "missing", "extra");
    assertTrue(log.toString().contains("reconcile domain=user table=users phase=before"));
    assertTrue(log.toString().contains("reconcile domain=user table=follows phase=before"));
  }

  @Test
  void reconcileHashComparedAsStoredSameHashIsClean() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1");
    UserTestDb.insertUser(dst, "u1");
    UserTestDb.insertFollow(src, "u1", "u1");
    UserTestDb.insertFollow(dst, "u1", "u1");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift());
    assertEquals(r.before("users").sourceChecksum, r.before("users").targetChecksum);
    assertEquals(r.before("follows").sourceChecksum, r.before("follows").targetChecksum);
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());
    assertEquals(2, r.report.get("summary").get("clean").asInt());
  }

  @Test
  void reconcileEmptyTablesIsClean() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift());
    assertEquals("CLEAN", r.report.get("summary").get("status").asText());
    assertEquals(2, r.report.get("summary").get("clean").asInt());
  }

  @Test
  void reconcileReportsDuplicateFollowPairsWithoutTreatingThemAsDrift() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1");
    UserTestDb.insertUser(dst, "u1");
    UserTestDb.insertFollow(src, "u1", "u2");
    UserTestDb.insertFollow(src, "u1", "u2");
    UserTestDb.insertFollow(dst, "u1", "u2");

    Reconcile.Outcome r = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, r.remainingDrift(), "a duplicate row is not missing data");
    assertEquals(1, r.before("follows").duplicateInSource.size());
    assertEquals(0, r.before("follows").duplicateInTarget.size());
    JsonNode f = r.report.get("tables").get(1);
    assertEquals(1, f.get("duplicateKeysInMonolithTotal").asInt());
    assertEquals("u1", f.get("duplicateKeysInMonolith").get(0).get("userId").asText());
    assertEquals("u2", f.get("duplicateKeysInMonolith").get(0).get("followId").asText());
    assertTrue(log.toString().contains("table=follows phase=before"));
    assertTrue(log.toString().contains("duplicateKeys=1/0"));
  }

  @Test
  void repairToTargetConvergesBothTablesInOnePass() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "kept");
    UserTestDb.insertUser(dst, "kept");
    UserTestDb.insertUser(src, "missing");
    UserTestDb.insertUser(src, "diverged");
    UserTestDb.insertUser(
        dst,
        "diverged",
        "name-diverged",
        UserTestDb.hash("stale"),
        "diverged@example.com",
        "service edit",
        "");
    UserTestDb.insertUser(dst, "extra");
    UserTestDb.insertFollow(src, "kept", "missing");
    UserTestDb.insertFollow(dst, "extra", "kept");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    o.report = dir.resolve("r.json");
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(5, r.before("users").driftRows() + r.before("follows").driftRows());
    assertEquals(2, r.inserted, "1 user + 1 follow");
    assertEquals(1, r.updated, "the diverged user (bio + password)");
    assertEquals(2, r.deleted, "1 user + 1 follow");
    assertEquals(0, r.remainingDrift());
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst));
    assertEquals(UserTestDb.follows(src), UserTestDb.follows(dst));
    assertEquals(UserTestDb.hash("diverged"), UserTestDb.columnOf(dst, "diverged", "password"));

    JsonNode report = new ObjectMapper().readTree(Files.readAllBytes(o.report));
    assertEquals("to-target", report.get("repair").get("mode").asText());
    assertEquals(0, report.get("repair").get("divergedAfter").asInt());
    assertEquals("CLEAN", report.get("summary").get("status").asText());
    assertNoHashLeaks(new String(Files.readAllBytes(o.report)), "kept", "missing", "diverged");
    assertNoHashLeaks(log.toString(), "kept", "missing", "diverged", "extra", "stale");
  }

  @Test
  void repairToTargetWithoutDeleteExtrasKeepsServiceOnlyRows() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "missing");
    UserTestDb.insertUser(dst, "extra");
    UserTestDb.insertFollow(dst, "extra", "missing");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(1, r.inserted);
    assertEquals(0, r.deleted);
    assertEquals(2, r.remainingDrift(), "the extra user and follow remain and are reported");
    assertEquals(List.of("extra", "missing"), UserTestDb.userIds(dst));
    assertEquals(List.of("extra>missing"), UserTestDb.follows(dst));
    assertTrue(log.toString().contains("extras kept"));
  }

  @Test
  void repairToSourceConvergesBothTablesInOnePass() throws Exception {
    Path mono = UserTestDb.create(dir, "dev.db");
    Path svc = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(svc, "new-in-service");
    UserTestDb.insertUser(svc, "edited");
    UserTestDb.insertUser(
        mono, "edited", "name-edited", UserTestDb.hash("old"), "edited@example.com", "stale", "");
    UserTestDb.insertUser(mono, "deleted-in-service");
    UserTestDb.insertFollow(svc, "new-in-service", "edited");
    UserTestDb.insertFollow(mono, "deleted-in-service", "edited");

    Reconcile.Options o = opts(mono, svc);
    o.repair = Reconcile.Repair.TO_SOURCE;
    o.deleteExtras = true;
    o.authoritative = "service";
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(2, r.inserted);
    assertEquals(1, r.updated);
    assertEquals(2, r.deleted);
    assertEquals(0, r.remainingDrift());
    assertEquals(UserTestDb.users(svc), UserTestDb.users(mono));
    assertEquals(UserTestDb.follows(svc), UserTestDb.follows(mono));
    assertEquals(UserTestDb.hash("edited"), UserTestDb.columnOf(mono, "edited", "password"));
    assertEquals("service", r.report.get("authoritative").asText());
    assertNoHashLeaks(log.toString(), "new-in-service", "edited", "old", "deleted-in-service");
  }

  @Test
  void repairMaxRepairCountsBothTablesAndWritesNothing() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    for (int i = 0; i < 3; i++) {
      UserTestDb.insertUser(src, "m" + i);
      UserTestDb.insertFollow(src, "m" + i, "m0");
    }

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.maxRepair = 5;
    assertThrows(SyncException.class, () -> new Reconcile(o, out).run());
    assertEquals(0, UserTestDb.countUsers(dst));
    assertEquals(0, UserTestDb.countFollows(dst));

    o.maxRepair = 6;
    assertEquals(0, new Reconcile(o, out).run().remainingDrift());
  }

  // ---------------------------------------------------------------- unique conflicts

  @Test
  void reconcileReportsUsernameHeldByAnotherIdAsConflictAndDoesNotCrashOnRepair() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "jane", UserTestDb.hash("u1"), "u1@x.io", "", "");
    UserTestDb.insertUser(dst, "svc-9", "jane", UserTestDb.hash("svc-9"), "svc@x.io", "", "");
    UserTestDb.insertUser(src, "ok");

    Reconcile.Outcome report = new Reconcile(opts(src, dst), out).run();
    Reconcile.Diff users = report.before("users");
    assertEquals(2, users.missingInTarget.size());
    assertEquals(1, users.extraInTarget.size());
    assertEquals(1, users.conflictsInTarget.size());
    Conflict c = users.conflictsInTarget.get(0);
    assertEquals("u1", c.row.key[0]);
    assertEquals("username", c.column);
    assertEquals("jane", c.value);
    assertEquals("svc-9", c.conflictingKey);
    assertEquals(1, users.conflictsInSource.size());
    assertEquals("svc-9", users.conflictsInSource.get(0).row.key[0]);
    assertEquals("u1", users.conflictsInSource.get(0).conflictingKey);
    JsonNode table = report.report.get("tables").get(0);
    JsonNode conflict = table.get("uniqueConflictsInService").get(0);
    assertEquals("u1", conflict.get("id").asText());
    assertEquals("username", conflict.get("column").asText());
    assertEquals("jane", conflict.get("value").asText());
    assertEquals("svc-9", conflict.get("conflictingId").asText());
    assertEquals(1, table.get("uniqueConflictsInMonolithTotal").asInt());
    assertTrue(log.toString().contains("conflicts=1/1"));

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome partial = new Reconcile(o, out).run();
    assertEquals(1, partial.inserted, "only 'ok' is inserted");
    assertEquals(List.of("ok", "svc-9"), UserTestDb.userIds(dst));
    assertEquals(2, partial.remainingDrift(), "u1 still missing + svc-9 still extra");
    assertTrue(log.toString().contains("reconcile table=users skipped=1 rows"));

    o.deleteExtras = true;
    Reconcile.Outcome full = new Reconcile(o, out).run();
    assertEquals(1, full.deleted);
    assertEquals(1, full.inserted);
    assertEquals(0, full.remainingDrift());
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst));
    assertNoHashLeaks(log.toString(), "u1", "svc-9", "ok");
  }

  @Test
  void reconcileEmailClashAndSwapAreBlockedUntilResolvedByHand() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "a", UserTestDb.hash("u1"), "one@x.io", "", "");
    UserTestDb.insertUser(src, "u2", "b", UserTestDb.hash("u2"), "two@x.io", "", "");
    UserTestDb.insertUser(dst, "u1", "a", UserTestDb.hash("u1"), "two@x.io", "", "");
    UserTestDb.insertUser(dst, "u2", "b", UserTestDb.hash("u2"), "one@x.io", "", "");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    Reconcile.Diff users = r.before("users");
    assertEquals(2, users.diverged.size());
    assertEquals(List.of("email"), users.diverged.get(0).columns);
    assertEquals(2, users.conflictsInTarget.size());
    assertEquals("email", users.conflictsInTarget.get(0).column);
    assertEquals(0, r.updated, "neither update can be applied without violating email UNIQUE");
    assertEquals(2, r.remainingDrift());
    assertEquals("two@x.io", UserTestDb.columnOf(dst, "u1", "email"));
    assertEquals("one@x.io", UserTestDb.columnOf(dst, "u2", "email"));
  }

  @Test
  void reconcileDivergedUsernameMovingToAFreeValueIsRepaired() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(src, "u1", "new-name", UserTestDb.hash("u1"), "u1@x.io", "", "");
    UserTestDb.insertUser(dst, "u1", "old-name", UserTestDb.hash("u1"), "u1@x.io", "", "");

    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(List.of("username"), r.before("users").diverged.get(0).columns);
    assertTrue(r.before("users").conflictsInTarget.isEmpty());
    assertEquals(1, r.updated);
    assertEquals(0, r.remainingDrift());
    assertEquals("new-name", UserTestDb.columnOf(dst, "u1", "username"));
  }

  // ---------------------------------------------------------------- CLI

  @Test
  void cliBackfillReconcileRepairReverseBackfillRoundTrip() throws Exception {
    Path mono = UserTestDb.create(dir, "dev.db");
    Path svc = UserTestDb.create(dir, "user.db");
    UserTestDb.insertUser(mono, "u1");
    UserTestDb.insertUser(mono, "u2");
    UserTestDb.insertFollow(mono, "u1", "u2");
    Path report = dir.resolve("reports/user.json");

    assertEquals(
        Main.EXIT_OK,
        run(
            "backfill",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString(),
            "--chunk",
            "1"));
    assertTrue(log.toString().contains("backfill done domain=user tables=2"));
    UserTestDb.updateUser(svc, "u2", "password", UserTestDb.hash("rotated"));
    assertEquals(
        Main.EXIT_DRIFT,
        run(
            "reconcile",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString(),
            "--report",
            report.toString()));
    String json = new String(Files.readAllBytes(report));
    JsonNode node = new ObjectMapper().readTree(json);
    assertEquals("user", node.get("domain").asText());
    assertEquals("DRIFT", node.get("summary").get("status").asText());
    assertEquals(
        "password",
        node.get("tables").get(0).get("diverged").get(0).get("columns").get(0).asText());
    assertNoHashLeaks(json, "u1", "u2", "rotated");

    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString(),
            "--repair",
            "to-target",
            "--delete-extras",
            "--max-repair",
            "10"));
    assertEquals(UserTestDb.users(mono), UserTestDb.users(svc));

    // state C: the service owns writes, then rollback copies them back
    UserTestDb.insertUser(svc, "u3");
    UserTestDb.insertFollow(svc, "u3", "u1");
    assertEquals(
        Main.EXIT_OK,
        run(
            "reverse-backfill",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString()));
    assertEquals(
        Main.EXIT_OK,
        run(
            "reconcile",
            "--domain",
            "user",
            "--source",
            mono.toString(),
            "--target",
            svc.toString(),
            "--authoritative",
            "service"));
    assertEquals(UserTestDb.users(svc), UserTestDb.users(mono));
    assertEquals(UserTestDb.follows(svc), UserTestDb.follows(mono));
    assertNoHashLeaks(log.toString(), "u1", "u2", "u3", "rotated");
  }

  @Test
  void cliHelpMentionsUser() {
    assertEquals(Main.EXIT_OK, run("--help"));
    assertTrue(log.toString().contains("user     -> tables users"));
    assertTrue(log.toString().contains("never printed"));
    assertTrue(log.toString().contains("favorite|comment|tag|article|user"));
  }

  // ---------------------------------------------------------------- scale

  @Test
  void tenThousandRowsBackfillReconcileAndRepairCleanly() throws Exception {
    Path src = UserTestDb.create(dir, "dev.db");
    Path dst = UserTestDb.create(dir, "user.db");
    UserTestDb.insertMany(src, 5_000, 1);

    Backfill.Result b = backfill(src, dst, 1000).run();
    assertEquals(10_000, b.rowsRead);
    assertEquals(10_000, b.rowsInserted);
    assertEquals(0, b.conflicts);
    assertEquals(5_000, UserTestDb.countUsers(dst));
    assertEquals(5_000, UserTestDb.countFollows(dst));

    Reconcile.Outcome clean = new Reconcile(opts(src, dst), out).run();
    assertEquals(0, clean.remainingDrift());
    assertEquals(clean.before("users").sourceChecksum, clean.before("users").targetChecksum);

    UserTestDb.updateUser(dst, "user-004242", "bio", "drift");
    UserTestDb.deleteUser(dst, "user-000007");
    UserTestDb.deleteFollows(dst, "user-000008");
    UserTestDb.insertUser(dst, "zz-extra");
    Reconcile.Options o = opts(src, dst);
    o.repair = Reconcile.Repair.TO_TARGET;
    o.deleteExtras = true;
    Reconcile.Outcome r = new Reconcile(o, out).run();
    assertEquals(4, r.before("users").driftRows() + r.before("follows").driftRows());
    assertEquals(0, r.remainingDrift());
    assertEquals(UserTestDb.users(src), UserTestDb.users(dst));
    assertEquals(UserTestDb.follows(src), UserTestDb.follows(dst));
    assertFalse(r.report.get("tables").get(0).has("truncated"));
    assertNoHashLeaks(log.toString(), "user-004242", "user-000007", "zz-extra");
  }

  private static List<String> keys(List<Row> rows) {
    return rows.stream().map(r -> r.key[0]).collect(Collectors.toList());
  }
}
