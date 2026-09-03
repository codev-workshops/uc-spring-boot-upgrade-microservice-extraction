package io.spring.tools.favoritesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Set diff of {@code article_favorites} between the monolith DB ({@code --source}) and the service
 * DB ({@code --target}) with optional repair (05-data-sync-and-rollback-design.md §4).
 *
 * <p>Both key streams are read in {@code (article_id, user_id)} order and merge-joined, so memory
 * use is bounded by the size of the <em>difference</em>, not of the tables. Because the table has
 * no mutable payload, key-set equality is full equality and the {@code diverged} bucket is always
 * empty.
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
    public Path source;
    public Path target;
    public Path report;
    public Repair repair = Repair.NONE;
    public boolean deleteExtras = false;
    public String authoritative = "monolith";
    /** 0 = unlimited; otherwise the repair aborts if it would touch more rows than this. */
    public long maxRepair = -1;
  }

  public static final class Diff {
    public long sourceCount;
    public long targetCount;
    public String sourceChecksum;
    public String targetChecksum;
    public final List<FavoriteKey> missingInTarget = new ArrayList<>();
    public final List<FavoriteKey> extraInTarget = new ArrayList<>();

    public long driftRows() {
      return missingInTarget.size() + extraInTarget.size();
    }
  }

  public static final class Outcome {
    public final Diff before;
    public final Diff after;
    public final long inserted;
    public final long deleted;
    public final ObjectNode report;

    Outcome(Diff before, Diff after, long inserted, long deleted, ObjectNode report) {
      this.before = before;
      this.after = after;
      this.inserted = inserted;
      this.deleted = deleted;
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
  private final PrintStream out;

  public Reconcile(Options opts, PrintStream out) {
    this.opts = opts;
    this.out = out;
  }

  public Outcome run() throws SQLException {
    Instant runId = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Diff before;
    Diff after = null;
    long inserted = 0;
    long deleted = 0;
    boolean repairWrites = opts.repair != Repair.NONE;
    try (Connection src = FavoriteDb.open(opts.source, opts.repair != Repair.TO_SOURCE);
        Connection dst = FavoriteDb.open(opts.target, opts.repair != Repair.TO_TARGET)) {
      before = diff(src, dst);
      logSummary("before", before);
      if (repairWrites && before.driftRows() > 0) {
        Connection writeSide = opts.repair == Repair.TO_TARGET ? dst : src;
        List<FavoriteKey> toInsert =
            opts.repair == Repair.TO_TARGET ? before.missingInTarget : before.extraInTarget;
        List<FavoriteKey> toDelete =
            opts.repair == Repair.TO_TARGET ? before.extraInTarget : before.missingInTarget;
        if (!opts.deleteExtras) {
          toDelete = List.of();
        }
        long authoritativeRows =
            opts.repair == Repair.TO_TARGET ? before.sourceCount : before.targetCount;
        long limit = effectiveMaxRepair(authoritativeRows);
        long touched = toInsert.size() + toDelete.size();
        if (limit > 0 && touched > limit) {
          throw new SyncException(
              "repair would touch "
                  + touched
                  + " rows, above --max-repair "
                  + limit
                  + "; mass drift means dual-write is broken - fix that first or pass an explicit"
                  + " --max-repair");
        }
        inserted = apply(writeSide, toInsert, toDelete);
        deleted = toDelete.size();
        out.println(
            "reconcile repair="
                + opts.repair.name().toLowerCase().replace('_', '-')
                + " inserted="
                + inserted
                + " deleted="
                + deleted
                + (opts.deleteExtras ? "" : " (extras kept; pass --delete-extras to remove them)"));
        after = diff(src, dst);
        logSummary("after", after);
      }
    }
    ObjectNode report = report(runId, before, after, inserted, deleted);
    if (opts.report != null) {
      writeReport(report);
    }
    return new Outcome(before, after, inserted, deleted, report);
  }

  long effectiveMaxRepair(long authoritativeRows) {
    if (opts.maxRepair >= 0) {
      return opts.maxRepair;
    }
    return Math.max(1000, authoritativeRows / 100);
  }

  private void logSummary(String phase, Diff d) {
    out.println(
        "reconcile domain=favorite table="
            + FavoriteDb.TABLE
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
            + " diverged=0 status="
            + (d.driftRows() == 0 ? "CLEAN" : "DRIFT"));
  }

  static Diff diff(Connection src, Connection dst) throws SQLException {
    Diff d = new Diff();
    d.sourceCount = FavoriteDb.count(src);
    d.targetCount = FavoriteDb.count(dst);
    MessageDigest srcDigest = sha256();
    MessageDigest dstDigest = sha256();
    try (PreparedStatement sps = FavoriteDb.orderedKeys(src);
        PreparedStatement dps = FavoriteDb.orderedKeys(dst);
        ResultSet s = sps.executeQuery();
        ResultSet t = dps.executeQuery()) {
      FavoriteKey sk = next(s, srcDigest);
      FavoriteKey tk = next(t, dstDigest);
      while (sk != null || tk != null) {
        int c = sk == null ? 1 : tk == null ? -1 : sk.compareTo(tk);
        if (c == 0) {
          sk = next(s, srcDigest);
          tk = next(t, dstDigest);
        } else if (c < 0) {
          d.missingInTarget.add(sk);
          sk = next(s, srcDigest);
        } else {
          d.extraInTarget.add(tk);
          tk = next(t, dstDigest);
        }
      }
    }
    d.sourceChecksum = hex(srcDigest.digest());
    d.targetChecksum = hex(dstDigest.digest());
    return d;
  }

  private static FavoriteKey next(ResultSet rs, MessageDigest digest) throws SQLException {
    if (!rs.next()) {
      return null;
    }
    FavoriteKey k = new FavoriteKey(rs.getString(1), rs.getString(2));
    digest.update((k.articleId + "|" + k.userId + "\n").getBytes(StandardCharsets.UTF_8));
    return k;
  }

  private static long apply(Connection c, List<FavoriteKey> insert, List<FavoriteKey> delete)
      throws SQLException {
    boolean auto = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      long before = FavoriteDb.totalChanges(c);
      try (PreparedStatement ps = FavoriteDb.insertOrIgnore(c)) {
        for (FavoriteKey k : insert) {
          ps.setString(1, k.articleId);
          ps.setString(2, k.userId);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      long inserted = FavoriteDb.totalChanges(c) - before;
      try (PreparedStatement ps = FavoriteDb.deleteByKey(c)) {
        for (FavoriteKey k : delete) {
          ps.setString(1, k.articleId);
          ps.setString(2, k.userId);
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

  private ObjectNode report(Instant runId, Diff before, Diff after, long inserted, long deleted) {
    ObjectNode root = JSON.createObjectNode();
    root.put("runId", DateTimeFormatter.ISO_INSTANT.format(runId));
    root.put("domain", "favorite");
    root.put("authoritative", opts.authoritative);
    root.put("graceSeconds", 0);
    ArrayNode tables = root.putArray("tables");
    ObjectNode t = tables.addObject();
    t.put("table", FavoriteDb.TABLE);
    t.put("monolithCount", before.sourceCount);
    t.put("serviceCount", before.targetCount);
    t.put("monolithChecksum", before.sourceChecksum);
    t.put("serviceChecksum", before.targetChecksum);
    boolean truncated = keys(t, "missingInService", before.missingInTarget);
    truncated |= keys(t, "extraInService", before.extraInTarget);
    t.putArray("diverged");
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
      if (after != null) {
        repair.put("monolithCountAfter", after.sourceCount);
        repair.put("serviceCountAfter", after.targetCount);
        repair.put("missingInServiceAfter", after.missingInTarget.size());
        repair.put("extraInServiceAfter", after.extraInTarget.size());
      }
    }
    return root;
  }

  private static boolean keys(ObjectNode parent, String field, List<FavoriteKey> keys) {
    ArrayNode arr = parent.putArray(field);
    int n = Math.min(keys.size(), REPORT_TRUNCATE_AT);
    for (int i = 0; i < n; i++) {
      ObjectNode k = arr.addObject();
      k.put("articleId", keys.get(i).articleId);
      k.put("userId", keys.get(i).userId);
    }
    parent.put(field + "Total", keys.size());
    return keys.size() > REPORT_TRUNCATE_AT;
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
