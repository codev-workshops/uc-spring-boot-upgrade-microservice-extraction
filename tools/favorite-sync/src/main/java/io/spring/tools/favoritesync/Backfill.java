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
 * One-off copy of {@code article_favorites} from a source database into a target database
 * (05-data-sync-and-rollback-design.md §3).
 *
 * <p>Procedure: record T0, take an online-backup snapshot of the source, then walk the snapshot in
 * keyset order on {@code (article_id, user_id)} in chunks. Every chunk is one {@code INSERT OR
 * IGNORE} transaction on the target, so the job is idempotent, re-runnable and restartable: a crash
 * mid-chunk rolls that chunk back and the next run simply re-inserts it.
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

  private final Path source;
  private final Path target;
  private final int chunkSize;
  private final PrintStream out;
  private final ChunkListener listener;

  public Backfill(Path source, Path target, int chunkSize, PrintStream out) {
    this(source, target, chunkSize, out, (i, n) -> {});
  }

  public Backfill(
      Path source, Path target, int chunkSize, PrintStream out, ChunkListener listener) {
    if (chunkSize <= 0) {
      throw new SyncException("--chunk must be > 0");
    }
    this.source = source;
    this.target = target;
    this.chunkSize = chunkSize;
    this.out = out;
    this.listener = listener;
  }

  public Result run() throws SQLException {
    Instant t0 = Instant.now();
    out.println("backfill source=" + source + " target=" + target + " chunk=" + chunkSize);
    out.println("backfill T0=" + t0);

    Path snapshot;
    try {
      snapshot = Files.createTempFile("favorite-sync-snapshot-", ".db");
    } catch (IOException e) {
      throw new SyncException("cannot create snapshot file", e);
    }
    try {
      FavoriteDb.snapshot(source, snapshot);
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
    try (Connection src = FavoriteDb.open(snapshot, true);
        Connection dst = FavoriteDb.open(target, false)) {
      long sourceCount = FavoriteDb.count(src);
      out.println(
          "backfill sourceRows=" + sourceCount + " targetRowsBefore=" + FavoriteDb.count(dst));
      dst.setAutoCommit(false);

      FavoriteKey cursor = null;
      while (true) {
        List<FavoriteKey> chunk = nextChunk(src, cursor);
        if (chunk.isEmpty()) {
          break;
        }
        long before = FavoriteDb.totalChanges(dst);
        try (PreparedStatement ins = FavoriteDb.insertOrIgnore(dst)) {
          for (FavoriteKey k : chunk) {
            ins.setString(1, k.articleId);
            ins.setString(2, k.userId);
            ins.addBatch();
          }
          ins.executeBatch();
        }
        long insertedInChunk = FavoriteDb.totalChanges(dst) - before;
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
      long after = FavoriteDb.count(dst);
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

  private List<FavoriteKey> nextChunk(Connection src, FavoriteKey after) throws SQLException {
    String sql =
        "select article_id, user_id from "
            + FavoriteDb.TABLE
            + (after == null ? "" : " where (article_id, user_id) > (?, ?)")
            + " order by article_id, user_id limit ?";
    List<FavoriteKey> keys = new ArrayList<>(chunkSize);
    try (PreparedStatement ps = src.prepareStatement(sql)) {
      int i = 1;
      if (after != null) {
        ps.setString(i++, after.articleId);
        ps.setString(i++, after.userId);
      }
      ps.setInt(i, chunkSize);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          keys.add(new FavoriteKey(rs.getString(1), rs.getString(2)));
        }
      }
    }
    return keys;
  }
}
