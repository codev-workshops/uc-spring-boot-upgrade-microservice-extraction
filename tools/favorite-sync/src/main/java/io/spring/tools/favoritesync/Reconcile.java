package io.spring.tools.favoritesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Diff of every {@link SyncTable} of a {@link Domain} between the monolith DB ({@code --source})
 * and the service DB ({@code --target}) with optional repair (05-data-sync-and-rollback-design.md
 * §4).
 *
 * <p>Both row streams are read in natural-key order and merge-joined, so memory use is bounded by
 * the size of the <em>difference</em>, not of the tables. Rows present on both sides whose payload
 * differs land in the {@code diverged} bucket (always empty for key-only tables such as {@code
 * article_favorites} and {@code article_tags}). For a table whose key SQLite does not enforce
 * ({@code article_tags}) repeated keys are collapsed to one logical row and reported as duplicates;
 * a repair never multiplies them.
 *
 * <p>For a table with a UNIQUE payload column ({@code articles.slug}) every row a repair would
 * insert or update on the other side is first checked against that side: a value already held there
 * by a different key is a {@link Conflict}. Conflicts are reported ({@code uniqueConflictsIn*}),
 * excluded from the repair (they would be silently ignored or fail) and therefore remain as drift,
 * unless the clashing row is itself removed by {@code --delete-extras} in the same run — extras are
 * deleted before anything is inserted so such a repair converges in one pass.
 *
 * <p>With a multi-table domain every table is diffed, then — if repairing — the {@code
 * --max-repair} guard is evaluated over the whole run before anything is written, so a mass-drift
 * run touches no table at all.
 */
public final class Reconcile {

  public static final int REPORT_TRUNCATE_AT = 1000;

  public enum Repair {
    NONE,
    TO_TARGET,
    TO_SOURCE;

    public static Repair parse(String s) {
      switch (s) {
        case "none":
          return NONE;
        case "to-target":
          return TO_TARGET;
        case "to-source":
          return TO_SOURCE;
        default:
          throw new SyncException("--repair must be one of none|to-target|to-source, got: " + s);
      }
    }
  }

  public static final class Options {
    public Domain domain = Domain.FAVORITE;
    public Path source;
    public Path target;
    public Path report;
    public Repair repair = Repair.NONE;
    public boolean deleteExtras = false;
    public String authoritative = "monolith";
    /** 0 = unlimited; otherwise the repair aborts if it would touch more rows than this. */
    public long maxRepair = -1;
  }

  /** A key present on both sides with different payloads. */
  public static final class Divergence {
    public final Row source;
    public final Row target;
    public final List<String> columns;

    Divergence(Row source, Row target, List<String> columns) {
      this.source = source;
      this.target = target;
      this.columns = columns;
    }
  }

  public static final class Diff {
    public SyncTable table;
    public long sourceCount;
    public long targetCount;
    public String sourceChecksum;
    public String targetChecksum;
    public final List<Row> missingInTarget = new ArrayList<>();
    public final List<Row> extraInTarget = new ArrayList<>();
    public final List<Divergence> diverged = new ArrayList<>();
    /** Keys stored more than once on a side whose key has no unique constraint. */
    public final List<Row> duplicateInSource = new ArrayList<>();

    public final List<Row> duplicateInTarget = new ArrayList<>();
    /** Source rows (missing or diverged) whose unique value another key holds in the target. */
    public final List<Conflict> conflictsInTarget = new ArrayList<>();
    /** Target rows (extra or diverged) whose unique value another key holds in the source. */
    public final List<Conflict> conflictsInSource = new ArrayList<>();

    public long driftRows() {
      return missingInTarget.size() + extraInTarget.size() + diverged.size();
    }
  }

  /** What a repair would write for one table. */
  private static final class Plan {
    final Diff diff;
    final List<Row> insert;
    final List<Row> delete;
    final List<Row> update;

    Plan(Diff diff, List<Row> insert, List<Row> delete, List<Row> update) {
      this.diff = diff;
      this.insert = insert;
      this.delete = delete;
      this.update = update;
    }

    long touched() {
      return insert.size() + delete.size() + update.size();
    }
  }

  public static final class Outcome {
    public final List<Diff> beforeTables;
    public final List<Diff> afterTables;
    /** First table of the domain, for the single-table domains. */
    public final Diff before;

    public final Diff after;
    public final long inserted;
    public final long deleted;
    public final long updated;
    public final ObjectNode report;

    Outcome(
        List<Diff> beforeTables,
        List<Diff> afterTables,
        long inserted,
        long deleted,
        long updated,
        ObjectNode report) {
      this.beforeTables = List.copyOf(beforeTables);
      this.afterTables = afterTables == null ? null : List.copyOf(afterTables);
      this.before = this.beforeTables.get(0);
      this.after = this.afterTables == null ? null : this.afterTables.get(0);
      this.inserted = inserted;
      this.deleted = deleted;
      this.updated = updated;
      this.report = report;
    }

    public Diff before(String table) {
      return find(beforeTables, table);
    }

    public Diff after(String table) {
      return afterTables == null ? null : find(afterTables, table);
    }

    /** Drift that remains after this run (post-repair if repair ran), over every table. */
    public long remainingDrift() {
      List<Diff> diffs = afterTables == null ? beforeTables : afterTables;
      long drift = 0;
      for (Diff d : diffs) {
        drift += d.driftRows();
      }
      return drift;
    }

    private static Diff find(List<Diff> diffs, String table) {
      for (Diff d : diffs) {
        if (d.table.table.equals(table)) {
          return d;
        }
      }
      throw new IllegalArgumentException("no such table in this run: " + table);
    }
  }

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Options opts;
  private final Domain domain;
  private final PrintStream out;

  public Reconcile(Options opts, PrintStream out) {
    this.opts = opts;
    this.domain = opts.domain;
    this.out = out;
  }

  public Outcome run() throws SQLException {
    Instant runId = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    List<Diff> before = new ArrayList<>();
    List<Diff> after = null;
    long inserted = 0;
    long deleted = 0;
    long updated = 0;
    boolean toTarget = opts.repair == Repair.TO_TARGET;
    try (Connection src = SyncDb.open(domain, opts.source, opts.repair != Repair.TO_SOURCE);
        Connection dst = SyncDb.open(domain, opts.target, opts.repair != Repair.TO_TARGET)) {
      for (SyncTable table : domain.tables) {
        Diff d = diff(table, src, dst);
        logSummary("before", d);
        before.add(d);
      }
      if (opts.repair != Repair.NONE && driftRows(before) > 0) {
        List<Plan> plans = new ArrayList<>(before.size());
        long touched = 0;
        long authoritativeRows = 0;
        for (Diff d : before) {
          List<Row> toDelete =
              opts.deleteExtras ? (toTarget ? d.extraInTarget : d.missingInTarget) : List.<Row>of();
          Set<Row> blocked =
              blockedRows(toTarget ? d.conflictsInTarget : d.conflictsInSource, toDelete);
          List<Row> toInsert = new ArrayList<>();
          for (Row r : toTarget ? d.missingInTarget : d.extraInTarget) {
            if (!blocked.contains(r)) {
              toInsert.add(r);
            }
          }
          List<Row> toUpdate = new ArrayList<>(d.diverged.size());
          for (Divergence div : d.diverged) {
            Row authoritative = toTarget ? div.source : div.target;
            if (!blocked.contains(authoritative)) {
              toUpdate.add(authoritative);
            }
          }
          if (!blocked.isEmpty()) {
            out.println(
                "reconcile table="
                    + d.table.table
                    + " skipped="
                    + blocked.size()
                    + " rows whose unique value is held by another key on the repaired side");
          }
          Plan plan = new Plan(d, toInsert, toDelete, toUpdate);
          plans.add(plan);
          touched += plan.touched();
          authoritativeRows += toTarget ? d.sourceCount : d.targetCount;
        }
        long limit = effectiveMaxRepair(authoritativeRows);
        if (limit > 0 && touched > limit) {
          throw new SyncException(
              "repair would touch "
                  + touched
                  + " rows, above --max-repair "
                  + limit
                  + "; mass drift means dual-write is broken - fix that first or pass an explicit"
                  + " --max-repair");
        }
        Connection writeSide = toTarget ? dst : src;
        for (Plan plan : plans) {
          inserted += apply(plan.diff.table, writeSide, plan.insert, plan.delete, plan.update);
          deleted += plan.delete.size();
          updated += plan.update.size();
        }
        out.println(
            "reconcile repair="
                + opts.repair.name().toLowerCase().replace('_', '-')
                + " inserted="
                + inserted
                + " deleted="
                + deleted
                + " updated="
                + updated
                + (opts.deleteExtras ? "" : " (extras kept; pass --delete-extras to remove them)"));
        after = new ArrayList<>();
        for (SyncTable table : domain.tables) {
          Diff d = diff(table, src, dst);
          logSummary("after", d);
          after.add(d);
        }
      }
    }
    ObjectNode report = report(runId, before, after, inserted, deleted, updated);
    if (opts.report != null) {
      writeReport(report);
    }
    return new Outcome(before, after, inserted, deleted, updated, report);
  }

  /** Conflicting rows that stay blocked: their clashing counterpart is not deleted in this run. */
  private static Set<Row> blockedRows(List<Conflict> conflicts, List<Row> toDelete) {
    Set<Row> blocked = new HashSet<>();
    if (conflicts.isEmpty()) {
      return blocked;
    }
    Set<String> deletedKeys = new HashSet<>();
    for (Row r : toDelete) {
      deletedKeys.add(r.key[0]);
    }
    for (Conflict c : conflicts) {
      if (!deletedKeys.contains(c.conflictingKey)) {
        blocked.add(c.row);
      }
    }
    return blocked;
  }

  private static long driftRows(List<Diff> diffs) {
    long drift = 0;
    for (Diff d : diffs) {
      drift += d.driftRows();
    }
    return drift;
  }

  long effectiveMaxRepair(long authoritativeRows) {
    if (opts.maxRepair >= 0) {
      return opts.maxRepair;
    }
    return Math.max(1000, authoritativeRows / 100);
  }

  private void logSummary(String phase, Diff d) {
    out.println(
        "reconcile domain="
            + domain.domainName
            + " table="
            + d.table.table
            + " phase="
            + phase
            + " monolith="
            + d.sourceCount
            + " service="
            + d.targetCount
            + " missing="
            + d.missingInTarget.size()
            + " extra="
            + d.extraInTarget.size()
            + " diverged="
            + d.diverged.size()
            + (d.table.uniqueKey
                ? ""
                : " duplicateKeys=" + d.duplicateInSource.size() + "/" + d.duplicateInTarget.size())
            + (d.table.hasUniqueColumns()
                ? " conflicts=" + d.conflictsInTarget.size() + "/" + d.conflictsInSource.size()
                : "")
            + " status="
            + (d.driftRows() == 0 ? "CLEAN" : "DRIFT"));
  }

  static Diff diff(SyncTable table, Connection src, Connection dst) throws SQLException {
    Diff d = new Diff();
    d.table = table;
    d.sourceCount = SyncDb.count(table, src);
    d.targetCount = SyncDb.count(table, dst);
    MessageDigest srcDigest = sha256();
    MessageDigest dstDigest = sha256();
    try (PreparedStatement sps = SyncDb.orderedRows(table, src);
        PreparedStatement dps = SyncDb.orderedRows(table, dst);
        ResultSet s = sps.executeQuery();
        ResultSet t = dps.executeQuery()) {
      Stream source = new Stream(s, table, srcDigest, d.duplicateInSource);
      Stream target = new Stream(t, table, dstDigest, d.duplicateInTarget);
      Row sk = source.next();
      Row tk = target.next();
      while (sk != null || tk != null) {
        int c = sk == null ? 1 : tk == null ? -1 : sk.compareTo(tk);
        if (c == 0) {
          if (!sk.samePayload(tk)) {
            d.diverged.add(new Divergence(sk, tk, sk.differingColumns(tk, table)));
          }
          sk = source.next();
          tk = target.next();
        } else if (c < 0) {
          d.missingInTarget.add(sk);
          sk = source.next();
        } else {
          d.extraInTarget.add(tk);
          tk = target.next();
        }
      }
    }
    d.sourceChecksum = hex(srcDigest.digest());
    d.targetChecksum = hex(dstDigest.digest());
    if (table.hasUniqueColumns()) {
      Conflict.collect(table, dst, d.missingInTarget, d.conflictsInTarget);
      Conflict.collect(table, src, d.extraInTarget, d.conflictsInSource);
      List<Row> divergedSource = new ArrayList<>();
      List<Row> divergedTarget = new ArrayList<>();
      for (Divergence div : d.diverged) {
        if (!Collections.disjoint(div.columns, table.uniqueColumns)) {
          divergedSource.add(div.source);
          divergedTarget.add(div.target);
        }
      }
      Conflict.collect(table, dst, divergedSource, d.conflictsInTarget);
      Conflict.collect(table, src, divergedTarget, d.conflictsInSource);
    }
    return d;
  }

  /**
   * One side of the merge-join: reads rows in key order, feeds the checksum and collapses repeated
   * keys of an unconstrained table into a single logical row (the extra copies are collected as
   * duplicates and do not take part in the diff).
   */
  private static final class Stream {
    private final ResultSet rs;
    private final SyncTable table;
    private final MessageDigest digest;
    private final List<Row> duplicates;
    private Row pending;

    Stream(ResultSet rs, SyncTable table, MessageDigest digest, List<Row> duplicates)
        throws SQLException {
      this.rs = rs;
      this.table = table;
      this.digest = digest;
      this.duplicates = duplicates;
      this.pending = read();
    }

    Row next() throws SQLException {
      Row current = pending;
      pending = read();
      while (current != null && pending != null && current.compareTo(pending) == 0) {
        duplicates.add(pending);
        pending = read();
      }
      if (current != null) {
        digest.update(current.digestBytes());
      }
      return current;
    }

    private Row read() throws SQLException {
      return rs.next() ? Row.read(rs, table) : null;
    }
  }

  private long apply(
      SyncTable table, Connection c, List<Row> insert, List<Row> delete, List<Row> update)
      throws SQLException {
    boolean auto = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      try (PreparedStatement ps = SyncDb.deleteByKey(table, c)) {
        for (Row r : delete) {
          r.bindKey(ps);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      long before = SyncDb.totalChanges(c);
      try (PreparedStatement ps = SyncDb.insertIfAbsent(table, c)) {
        for (Row r : insert) {
          r.bindInsert(ps, table);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      long inserted = SyncDb.totalChanges(c) - before;
      if (!update.isEmpty()) {
        try (PreparedStatement ps = SyncDb.updateByKey(table, c)) {
          for (Row r : update) {
            r.bindPayloadThenKey(ps);
            ps.addBatch();
          }
          ps.executeBatch();
        }
      }
      c.commit();
      return inserted;
    } catch (SQLException | RuntimeException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(auto);
    }
  }

  private ObjectNode report(
      Instant runId,
      List<Diff> before,
      List<Diff> after,
      long inserted,
      long deleted,
      long updated) {
    ObjectNode root = JSON.createObjectNode();
    root.put("runId", DateTimeFormatter.ISO_INSTANT.format(runId));
    root.put("domain", domain.domainName);
    root.put("authoritative", opts.authoritative);
    root.put("graceSeconds", 0);
    ArrayNode tables = root.putArray("tables");
    for (Diff d : before) {
      table(tables.addObject(), d);
    }

    List<Diff> finalDiffs = after == null ? before : after;
    long driftTables = 0;
    for (Diff d : finalDiffs) {
      if (d.driftRows() > 0) {
        driftTables++;
      }
    }
    ObjectNode summary = root.putObject("summary");
    summary.put("drift", driftTables);
    summary.put("clean", finalDiffs.size() - driftTables);
    summary.put("driftRows", driftRows(finalDiffs));
    summary.put("status", driftRows(finalDiffs) == 0 ? "CLEAN" : "DRIFT");

    if (opts.repair != Repair.NONE) {
      ObjectNode repair = root.putObject("repair");
      repair.put("mode", opts.repair.name().toLowerCase().replace('_', '-'));
      repair.put("deleteExtras", opts.deleteExtras);
      repair.put("inserted", inserted);
      repair.put("deleted", deleted);
      repair.put("updated", updated);
      if (after != null) {
        long sourceAfter = 0;
        long targetAfter = 0;
        long missingAfter = 0;
        long extraAfter = 0;
        long divergedAfter = 0;
        for (Diff d : after) {
          sourceAfter += d.sourceCount;
          targetAfter += d.targetCount;
          missingAfter += d.missingInTarget.size();
          extraAfter += d.extraInTarget.size();
          divergedAfter += d.diverged.size();
        }
        repair.put("monolithCountAfter", sourceAfter);
        repair.put("serviceCountAfter", targetAfter);
        repair.put("missingInServiceAfter", missingAfter);
        repair.put("extraInServiceAfter", extraAfter);
        repair.put("divergedAfter", divergedAfter);
      }
    }
    return root;
  }

  private void table(ObjectNode t, Diff d) {
    t.put("table", d.table.table);
    t.put("monolithCount", d.sourceCount);
    t.put("serviceCount", d.targetCount);
    t.put("monolithChecksum", d.sourceChecksum);
    t.put("serviceChecksum", d.targetChecksum);
    boolean truncated = keys(t, d, "missingInService", d.missingInTarget);
    truncated |= keys(t, d, "extraInService", d.extraInTarget);
    truncated |= diverged(t, d, d.diverged);
    if (!d.table.uniqueKey) {
      truncated |= keys(t, d, "duplicateKeysInMonolith", d.duplicateInSource);
      truncated |= keys(t, d, "duplicateKeysInService", d.duplicateInTarget);
    }
    if (d.table.hasUniqueColumns()) {
      truncated |= conflicts(t, d, "uniqueConflictsInService", d.conflictsInTarget);
      truncated |= conflicts(t, d, "uniqueConflictsInMonolith", d.conflictsInSource);
    }
    if (truncated) {
      t.put("truncated", true);
    }
    t.put("status", d.driftRows() == 0 ? "CLEAN" : "DRIFT");
  }

  private void putKey(ObjectNode node, Diff d, Row row) {
    for (int i = 0; i < row.key.length; i++) {
      node.put(d.table.keyJsonNames.get(i), row.key[i]);
    }
  }

  private boolean keys(ObjectNode parent, Diff d, String field, List<Row> rows) {
    ArrayNode arr = parent.putArray(field);
    int n = Math.min(rows.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      putKey(arr.addObject(), d, rows.get(i));
    }
    parent.put(field + "Total", rows.size());
    return rows.size() > REPORT_TRUNCATE_AT;
  }

  private boolean diverged(ObjectNode parent, Diff d, List<Divergence> list) {
    ArrayNode arr = parent.putArray("diverged");
    int n = Math.min(list.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      Divergence div = list.get(i);
      ObjectNode node = arr.addObject();
      putKey(node, d, div.source);
      ArrayNode cols = node.putArray("columns");
      div.columns.forEach(cols::add);
    }
    if (d.table.hasPayload()) {
      parent.put("divergedTotal", list.size());
    }
    return list.size() > REPORT_TRUNCATE_AT;
  }

  private boolean conflicts(ObjectNode parent, Diff d, String field, List<Conflict> list) {
    ArrayNode arr = parent.putArray(field);
    int n = Math.min(list.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      Conflict c = list.get(i);
      ObjectNode node = arr.addObject();
      putKey(node, d, c.row);
      node.put("column", c.column);
      node.put("value", c.value);
      node.put("conflictingId", c.conflictingKey);
    }
    parent.put(field + "Total", list.size());
    return list.size() > REPORT_TRUNCATE_AT;
  }

  private void writeReport(ObjectNode report) {
    try {
      Path parent = opts.report.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(opts.report, JSON.writeValueAsBytes(report));
      out.println("reconcile report=" + opts.report);
    } catch (IOException e) {
      throw new SyncException("cannot write report " + opts.report, e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
