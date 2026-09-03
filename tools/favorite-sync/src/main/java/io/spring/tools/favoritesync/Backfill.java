package io.spring.tools.favoritesync;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off copy of a {@link Domain} table from a source database into a target database
 * (05-data-sync-and-rollback-design.md §3).
 *
 * <p>Procedure: record T0, take an online-backup snapshot of the source, then walk the snapshot in
 * keyset order on the natural key in chunks. Every chunk is one {@code INSERT OR IGNORE}
 * transaction on the target, so the job is idempotent, re-runnable and restartable: a crash
 * mid-chunk rolls that chunk back and the next run simply re-inserts it. Payload values are copied
 * as the raw stored SQLite values, never reformatted.
 */
public final class Backfill {

  /** Invoked after each committed chunk; lets tests inject a crash between chunks. */
  public interface ChunkListener {
    void afterChunk(int chunkIndex, long rowsInChunk) throws SQLException;
  }

  public static final class Result {
    public final Instant t0;
    public final long rowsRead;
    public final long rowsInserted;
    public final long rowsSkipped;
    public final int chunks;
    public final long targetCountAfter;

    Result(
        Instant t0,
        long rowsRead,
        long rowsInserted,
        long rowsSkipped,
        int chunks,
        long targetCountAfter) {
      this.t0 = t0;
      this.rowsRead = rowsRead;
      this.rowsInserted = rowsInserted;
      this.rowsSkipped = rowsSkipped;
      this.chunks = chunks;
      this.targetCountAfter = targetCountAfter;
    }
  }

  private final Domain domain;
  private final Path source;
  private final Path target;
  private final int chunkSize;
  private final PrintStream out;
  private final ChunkListener listener;

  public Backfill(Path source, Path target, int chunkSize, PrintStream out) {
    this(Domain.FAVORITE, source, target, chunkSize, out, (i, n) -> {});
  }

  public Backfill(
      Path source, Path target, int chunkSize, PrintStream out, ChunkListener listener) {
    this(Domain.FAVORITE, source, target, chunkSize, out, listener);
  }

  public Backfill(Domain domain, Path source, Path target, int chunkSize, PrintStream out) {
    this(domain, source, target, chunkSize, out, (i, n) -> {});
  }

  public Backfill(
      Domain domain,
      Path source,
      Path target,
      int chunkSize,
      PrintStream out,
      ChunkListener listener) {
    if (chunkSize <= 0) {
      throw new SyncException("--chunk must be > 0");
    }
    this.domain = domain;
    this.source = source;
    this.target = target;
    this.chunkSize = chunkSize;
    this.out = out;
    this.listener = listener;
  }

  public Result run() throws SQLException {
    Instant t0 = Instant.now();
    out.println(
        "backfill domain="
            + domain.domainName
            + " table="
            + domain.table
            + " source="
            + source
            + " target="
            + target
            + " chunk="
            + chunkSize);
    out.println("backfill T0=" + t0);

    Path snapshot;
    try {
      snapshot = Files.createTempFile("favorite-sync-snapshot-", ".db");
    } catch (IOException e) {
      throw new SyncException("cannot create snapshot file", e);
    }
    try {
      SyncDb.snapshot(domain, source, snapshot);
      out.println("backfill snapshot=" + snapshot);
      return copy(snapshot, t0);
    } finally {
      try {
        Files.deleteIfExists(snapshot);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  private Result copy(Path snapshot, Instant t0) throws SQLException {
    long read = 0;
    long inserted = 0;
    int chunks = 0;
    try (Connection src = SyncDb.open(domain, snapshot, true);
        Connection dst = SyncDb.open(domain, target, false)) {
      long sourceCount = SyncDb.count(domain, src);
      out.println(
          "backfill sourceRows=" + sourceCount + " targetRowsBefore=" + SyncDb.count(domain, dst));
      dst.setAutoCommit(false);

      Row cursor = null;
      while (true) {
        List<Row> chunk = nextChunk(src, cursor);
        if (chunk.isEmpty()) {
          break;
        }
        long before = SyncDb.totalChanges(dst);
        try (PreparedStatement ins = SyncDb.insertOrIgnore(domain, dst)) {
          for (Row r : chunk) {
            r.bindAll(ins);
            ins.addBatch();
          }
          ins.executeBatch();
        }
        long insertedInChunk = SyncDb.totalChanges(dst) - before;
        dst.commit();
        read += chunk.size();
        inserted += insertedInChunk;
        chunks++;
        cursor = chunk.get(chunk.size() - 1);
        out.println(
            "backfill chunk="
                + chunks
                + " rows="
                + chunk.size()
                + " inserted="
                + insertedInChunk
                + " skipped="
                + (chunk.size() - insertedInChunk)
                + " lastKey="
                + cursor);
        listener.afterChunk(chunks, chunk.size());
        if (chunk.size() < chunkSize) {
          break;
        }
      }
      long after = SyncDb.count(domain, dst);
      Result r = new Result(t0, read, inserted, read - inserted, chunks, after);
      out.println(
          "backfill done rowsRead="
              + r.rowsRead
              + " rowsInserted="
              + r.rowsInserted
              + " rowsSkipped="
              + r.rowsSkipped
              + " chunks="
              + r.chunks
              + " targetRowsAfter="
              + r.targetCountAfter
              + " T0="
              + r.t0);
      return r;
    }
  }

  private List<Row> nextChunk(Connection src, Row after) throws SQLException {
    List<Row> rows = new ArrayList<>(chunkSize);
    try (PreparedStatement ps = src.prepareStatement(domain.selectChunk(after != null))) {
      int i = 1;
      if (after != null) {
        after.bindKey(ps);
        i += after.key.length;
      }
      ps.setInt(i, chunkSize);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(Row.read(rs, domain));
        }
      }
    }
    return rows;
  }
}
