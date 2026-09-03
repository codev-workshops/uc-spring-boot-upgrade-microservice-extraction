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
import java.util.List;

/**
 * Diff of a {@link Domain} table between the monolith DB ({@code --source}) and the service DB
 * ({@code --target}) with optional repair (05-data-sync-and-rollback-design.md §4).
 *
 * <p>Both row streams are read in natural-key order and merge-joined, so memory use is bounded by
 * the size of the <em>difference</em>, not of the tables. Rows present on both sides whose payload
 * differs land in the {@code diverged} bucket (always empty for key-only tables such as {@code
 * article_favorites}).
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
    public long sourceCount;
    public long targetCount;
    public String sourceChecksum;
    public String targetChecksum;
    public final List<Row> missingInTarget = new ArrayList<>();
    public final List<Row> extraInTarget = new ArrayList<>();
    public final List<Divergence> diverged = new ArrayList<>();

    public long driftRows() {
      return missingInTarget.size() + extraInTarget.size() + diverged.size();
    }
  }

  public static final class Outcome {
    public final Diff before;
    public final Diff after;
    public final long inserted;
    public final long deleted;
    public final long updated;
    public final ObjectNode report;

    Outcome(Diff before, Diff after, long inserted, long deleted, long updated, ObjectNode report) {
      this.before = before;
      this.after = after;
      this.inserted = inserted;
      this.deleted = deleted;
      this.updated = updated;
      this.report = report;
    }

    /** Drift that remains after this run (post-repair if repair ran). */
    public long remainingDrift() {
      return (after == null ? before : after).driftRows();
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
    Diff before;
    Diff after = null;
    long inserted = 0;
    long deleted = 0;
    long updated = 0;
    boolean repairWrites = opts.repair != Repair.NONE;
    try (Connection src = SyncDb.open(domain, opts.source, opts.repair != Repair.TO_SOURCE);
        Connection dst = SyncDb.open(domain, opts.target, opts.repair != Repair.TO_TARGET)) {
      before = diff(domain, src, dst);
      logSummary("before", before);
      if (repairWrites && before.driftRows() > 0) {
        boolean toTarget = opts.repair == Repair.TO_TARGET;
        Connection writeSide = toTarget ? dst : src;
        List<Row> toInsert = toTarget ? before.missingInTarget : before.extraInTarget;
        List<Row> toDelete = toTarget ? before.extraInTarget : before.missingInTarget;
        List<Row> toUpdate = new ArrayList<>(before.diverged.size());
        for (Divergence d : before.diverged) {
          toUpdate.add(toTarget ? d.source : d.target);
        }
        if (!opts.deleteExtras) {
          toDelete = List.of();
        }
        long authoritativeRows = toTarget ? before.sourceCount : before.targetCount;
        long limit = effectiveMaxRepair(authoritativeRows);
        long touched = toInsert.size() + toDelete.size() + toUpdate.size();
        if (limit > 0 && touched > limit) {
          throw new SyncException(
              "repair would touch "
                  + touched
                  + " rows, above --max-repair "
                  + limit
                  + "; mass drift means dual-write is broken - fix that first or pass an explicit"
                  + " --max-repair");
        }
        inserted = apply(writeSide, toInsert, toDelete, toUpdate);
        deleted = toDelete.size();
        updated = toUpdate.size();
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
        after = diff(domain, src, dst);
        logSummary("after", after);
      }
    }
    ObjectNode report = report(runId, before, after, inserted, deleted, updated);
    if (opts.report != null) {
      writeReport(report);
    }
    return new Outcome(before, after, inserted, deleted, updated, report);
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
            + domain.table
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
            + " status="
            + (d.driftRows() == 0 ? "CLEAN" : "DRIFT"));
  }

  static Diff diff(Domain domain, Connection src, Connection dst) throws SQLException {
    Diff d = new Diff();
    d.sourceCount = SyncDb.count(domain, src);
    d.targetCount = SyncDb.count(domain, dst);
    MessageDigest srcDigest = sha256();
    MessageDigest dstDigest = sha256();
    try (PreparedStatement sps = SyncDb.orderedRows(domain, src);
        PreparedStatement dps = SyncDb.orderedRows(domain, dst);
        ResultSet s = sps.executeQuery();
        ResultSet t = dps.executeQuery()) {
      Row sk = next(s, domain, srcDigest);
      Row tk = next(t, domain, dstDigest);
      while (sk != null || tk != null) {
        int c = sk == null ? 1 : tk == null ? -1 : sk.compareTo(tk);
        if (c == 0) {
          if (!sk.samePayload(tk)) {
            d.diverged.add(new Divergence(sk, tk, sk.differingColumns(tk, domain)));
          }
          sk = next(s, domain, srcDigest);
          tk = next(t, domain, dstDigest);
        } else if (c < 0) {
          d.missingInTarget.add(sk);
          sk = next(s, domain, srcDigest);
        } else {
          d.extraInTarget.add(tk);
          tk = next(t, domain, dstDigest);
        }
      }
    }
    d.sourceChecksum = hex(srcDigest.digest());
    d.targetChecksum = hex(dstDigest.digest());
    return d;
  }

  private static Row next(ResultSet rs, Domain domain, MessageDigest digest) throws SQLException {
    if (!rs.next()) {
      return null;
    }
    Row r = Row.read(rs, domain);
    digest.update(r.digestBytes());
    return r;
  }

  private long apply(Connection c, List<Row> insert, List<Row> delete, List<Row> update)
      throws SQLException {
    boolean auto = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      long before = SyncDb.totalChanges(c);
      try (PreparedStatement ps = SyncDb.insertOrIgnore(domain, c)) {
        for (Row r : insert) {
          r.bindAll(ps);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      long inserted = SyncDb.totalChanges(c) - before;
      if (!update.isEmpty()) {
        try (PreparedStatement ps = SyncDb.updateByKey(domain, c)) {
          for (Row r : update) {
            r.bindPayloadThenKey(ps);
            ps.addBatch();
          }
          ps.executeBatch();
        }
      }
      try (PreparedStatement ps = SyncDb.deleteByKey(domain, c)) {
        for (Row r : delete) {
          r.bindKey(ps);
          ps.addBatch();
        }
        ps.executeBatch();
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
      Instant runId, Diff before, Diff after, long inserted, long deleted, long updated) {
    ObjectNode root = JSON.createObjectNode();
    root.put("runId", DateTimeFormatter.ISO_INSTANT.format(runId));
    root.put("domain", domain.domainName);
    root.put("authoritative", opts.authoritative);
    root.put("graceSeconds", 0);
    ArrayNode tables = root.putArray("tables");
    ObjectNode t = tables.addObject();
    t.put("table", domain.table);
    t.put("monolithCount", before.sourceCount);
    t.put("serviceCount", before.targetCount);
    t.put("monolithChecksum", before.sourceChecksum);
    t.put("serviceChecksum", before.targetChecksum);
    boolean truncated = keys(t, "missingInService", before.missingInTarget);
    truncated |= keys(t, "extraInService", before.extraInTarget);
    truncated |= diverged(t, before.diverged);
    if (truncated) {
      t.put("truncated", true);
    }
    t.put("status", before.driftRows() == 0 ? "CLEAN" : "DRIFT");

    Diff finalDiff = after == null ? before : after;
    ObjectNode summary = root.putObject("summary");
    summary.put("drift", finalDiff.driftRows() == 0 ? 0 : 1);
    summary.put("clean", finalDiff.driftRows() == 0 ? 1 : 0);
    summary.put("driftRows", finalDiff.driftRows());
    summary.put("status", finalDiff.driftRows() == 0 ? "CLEAN" : "DRIFT");

    if (opts.repair != Repair.NONE) {
      ObjectNode repair = root.putObject("repair");
      repair.put("mode", opts.repair.name().toLowerCase().replace('_', '-'));
      repair.put("deleteExtras", opts.deleteExtras);
      repair.put("inserted", inserted);
      repair.put("deleted", deleted);
      repair.put("updated", updated);
      if (after != null) {
        repair.put("monolithCountAfter", after.sourceCount);
        repair.put("serviceCountAfter", after.targetCount);
        repair.put("missingInServiceAfter", after.missingInTarget.size());
        repair.put("extraInServiceAfter", after.extraInTarget.size());
        repair.put("divergedAfter", after.diverged.size());
      }
    }
    return root;
  }

  private void putKey(ObjectNode node, Row row) {
    for (int i = 0; i < row.key.length; i++) {
      node.put(domain.keyJsonNames.get(i), row.key[i]);
    }
  }

  private boolean keys(ObjectNode parent, String field, List<Row> rows) {
    ArrayNode arr = parent.putArray(field);
    int n = Math.min(rows.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      putKey(arr.addObject(), rows.get(i));
    }
    parent.put(field + "Total", rows.size());
    return rows.size() > REPORT_TRUNCATE_AT;
  }

  private boolean diverged(ObjectNode parent, List<Divergence> list) {
    ArrayNode arr = parent.putArray("diverged");
    int n = Math.min(list.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      Divergence d = list.get(i);
      ObjectNode node = arr.addObject();
      putKey(node, d.source);
      ArrayNode cols = node.putArray("columns");
      d.columns.forEach(cols::add);
    }
    if (domain.hasPayload()) {
      parent.put("divergedTotal", list.size());
    }
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
