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
 * One-off copy of every {@link SyncTable} of a {@link Domain} from a source database into a target
 * database (05-data-sync-and-rollback-design.md §3).
 *
 * <p>Procedure: record T0, take an online-backup snapshot of the source, then walk each table of
 * the domain (in declaration order) in keyset order on the natural key in chunks. Every chunk is
 * one insert-if-absent transaction on the target, so the job is idempotent, re-runnable and
 * restartable: a crash mid-chunk rolls that chunk back and the next run simply re-inserts it.
 * Payload values are copied as the raw stored SQLite values, never reformatted.
 */
public final class Backfill {

  /** Invoked after each committed chunk; lets tests inject a crash between chunks. */
  public interface ChunkListener {
    void afterChunk(int chunkIndex, long rowsInChunk) throws SQLException;
  }

  /** Per-table numbers of one run. */
  public static final class TableResult {
    public final String table;
    public final long rowsRead;
    public final long rowsInserted;
    public final long rowsSkipped;
    public final int chunks;
    public final long targetCountAfter;

    TableResult(
        String table,
        long rowsRead,
        long rowsInserted,
        long rowsSkipped,
        int chunks,
        long targetCountAfter) {
      this.table = table;
      this.rowsRead = rowsRead;
      this.rowsInserted = rowsInserted;
      this.rowsSkipped = rowsSkipped;
      this.chunks = chunks;
      this.targetCountAfter = targetCountAfter;
    }
  }

  /** Totals over every table of the domain, plus the per-table breakdown. */
  public static final class Result {
    public final Instant t0;
    public final long rowsRead;
    public final long rowsInserted;
    public final long rowsSkipped;
    public final int chunks;
    public final long targetCountAfter;
    public final List<TableResult> tables;

    Result(Instant t0, List<TableResult> tables) {
      this.t0 = t0;
      this.tables = List.copyOf(tables);
      long read = 0;
      long inserted = 0;
      long skipped = 0;
      int chunkCount = 0;
      long after = 0;
      for (TableResult t : tables) {
        read += t.rowsRead;
        inserted += t.rowsInserted;
        skipped += t.rowsSkipped;
        chunkCount += t.chunks;
        after += t.targetCountAfter;
      }
      this.rowsRead = read;
      this.rowsInserted = inserted;
      this.rowsSkipped = skipped;
      this.chunks = chunkCount;
      this.targetCountAfter = after;
    }

    public TableResult table(String name) {
      for (TableResult t : tables) {
        if (t.table.equals(name)) {
          return t;
        }
      }
      throw new IllegalArgumentException("no such table in this run: " + name);
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
    Path snapshot;
    try {
      snapshot = Files.createTempFile("favorite-sync-snapshot-", ".db");
    } catch (IOException e) {
      throw new SyncException("cannot create snapshot file", e);
    }
    try {
      SyncDb.snapshot(domain, source, snapshot);
      List<TableResult> results = new ArrayList<>(domain.tables.size());
      int chunkIndex = 0;
      try (Connection src = SyncDb.open(domain, snapshot, true);
          Connection dst = SyncDb.open(domain, target, false)) {
        for (SyncTable table : domain.tables) {
          out.println(
              "backfill domain="
                  + domain.domainName
                  + " table="
                  + table.table
                  + " source="
                  + source
                  + " target="
                  + target
                  + " chunk="
                  + chunkSize);
          if (results.isEmpty()) {
            out.println("backfill T0=" + t0);
            out.println("backfill snapshot=" + snapshot);
          }
          TableResult r = copy(table, src, dst, chunkIndex, t0);
          chunkIndex += r.chunks;
          results.add(r);
        }
      }
      Result result = new Result(t0, results);
      if (domain.tables.size() > 1) {
        out.println(
            "backfill done domain="
                + domain.domainName
                + " tables="
                + domain.tables.size()
                + " rowsRead="
                + result.rowsRead
                + " rowsInserted="
                + result.rowsInserted
                + " rowsSkipped="
                + result.rowsSkipped
                + " chunks="
                + result.chunks
                + " T0="
                + result.t0);
      }
      return result;
    } finally {
      try {
        Files.deleteIfExists(snapshot);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  private TableResult copy(
      SyncTable table, Connection src, Connection dst, int chunkOffset, Instant t0)
      throws SQLException {
    long read = 0;
    long inserted = 0;
    int chunks = 0;
    long sourceCount = SyncDb.count(table, src);
    out.println(
        "backfill sourceRows=" + sourceCount + " targetRowsBefore=" + SyncDb.count(table, dst));
    dst.setAutoCommit(false);

    Row cursor = null;
    while (true) {
      List<Row> chunk = nextChunk(table, src, cursor);
      if (chunk.isEmpty()) {
        break;
      }
      long before = SyncDb.totalChanges(dst);
      try (PreparedStatement ins = SyncDb.insertIfAbsent(table, dst)) {
        for (Row r : chunk) {
          r.bindInsert(ins, table);
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
      listener.afterChunk(chunkOffset + chunks, chunk.size());
      if (chunk.size() < chunkSize) {
        break;
      }
    }
    TableResult r =
        new TableResult(
            table.table, read, inserted, read - inserted, chunks, SyncDb.count(table, dst));
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
            + t0
            + " table="
            + r.table);
    return r;
  }

  private List<Row> nextChunk(SyncTable table, Connection src, Row after) throws SQLException {
    List<Row> rows = new ArrayList<>(chunkSize);
    try (PreparedStatement ps = src.prepareStatement(table.selectChunk(after != null))) {
      int i = 1;
      if (after != null) {
        after.bindKey(ps);
        i += after.key.length;
      }
      ps.setInt(i, chunkSize);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(Row.read(rs, table));
        }
      }
    }
    return rows;
  }
}
