package io.spring.tools.favoritesync;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * favorite-sync CLI.
 *
 * <pre>
 * favorite-sync backfill         [--domain favorite|comment|tag|article] --source dev.db --target favorite.db [--chunk 5000]
 * favorite-sync reverse-backfill [--domain favorite|comment|tag|article] --source dev.db --target favorite.db [--chunk 5000]
 * favorite-sync reconcile        [--domain favorite|comment|tag|article] --source dev.db --target favorite.db [--report out.json]
 *                                [--repair none|to-target|to-source] [--delete-extras]
 *                                [--authoritative monolith|service] [--max-repair N]
 * </pre>
 *
 * {@code --domain} defaults to {@code favorite} ({@code article_favorites}); {@code comment} works
 * on the {@code comments} table keyed by {@code id}; {@code tag} covers {@code tags} and then
 * {@code article_tags} in a single run; {@code article} works on the {@code articles} table keyed
 * by {@code id} (tag tables are not touched — run {@code tag} first).
 *
 * <p>Exit codes: 0 success / zero drift; 1 drift remains (report-only or after repair) or a
 * backfill hit unique-column conflicts; 2 usage or runtime error.
 */
public final class Main {

  public static final int EXIT_OK = 0;
  public static final int EXIT_DRIFT = 1;
  public static final int EXIT_ERROR = 2;

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
      usage(out);
      return args.length == 0 ? EXIT_ERROR : EXIT_OK;
    }
    try {
      String command = args[0];
      Map<String, String> opts = parse(args);
      switch (command) {
        case "backfill":
          return backfill(opts, false, out);
        case "reverse-backfill":
          return backfill(opts, true, out);
        case "reconcile":
          return reconcile(opts, out);
        default:
          throw new SyncException("unknown command: " + command);
      }
    } catch (SyncException e) {
      err.println("error: " + e.getMessage());
      if (e.getCause() != null) {
        err.println("cause: " + e.getCause());
      }
      return EXIT_ERROR;
    } catch (SQLException e) {
      err.println("error: sqlite: " + e.getMessage());
      return EXIT_ERROR;
    }
  }

  private static int backfill(Map<String, String> opts, boolean reverse, PrintStream out)
      throws SQLException {
    Domain domain = domain(opts);
    Path source = required(opts, "source");
    Path target = required(opts, "target");
    int chunk = intOption(opts, "chunk", 5000);
    if (reverse) {
      out.println("reverse-backfill: copying " + target + " -> " + source);
      Path swap = source;
      source = target;
      target = swap;
    }
    Backfill.Result result = new Backfill(domain, source, target, chunk, out).run();
    return result.conflicts == 0 ? EXIT_OK : EXIT_DRIFT;
  }

  private static int reconcile(Map<String, String> opts, PrintStream out) throws SQLException {
    Reconcile.Options o = new Reconcile.Options();
    o.domain = domain(opts);
    o.source = required(opts, "source");
    o.target = required(opts, "target");
    o.report = opts.containsKey("report") ? Paths.get(opts.get("report")) : null;
    o.repair = Reconcile.Repair.parse(opts.getOrDefault("repair", "none"));
    o.deleteExtras = opts.containsKey("delete-extras");
    o.authoritative = opts.getOrDefault("authoritative", "monolith");
    if (!o.authoritative.equals("monolith") && !o.authoritative.equals("service")) {
      throw new SyncException("--authoritative must be monolith|service");
    }
    if (opts.containsKey("max-repair")) {
      o.maxRepair = intOption(opts, "max-repair", 0);
    }
    if (o.deleteExtras && o.repair == Reconcile.Repair.NONE) {
      throw new SyncException("--delete-extras requires --repair to-target|to-source");
    }
    Reconcile.Outcome outcome = new Reconcile(o, out).run();
    return outcome.remainingDrift() == 0 ? EXIT_OK : EXIT_DRIFT;
  }

  private static Domain domain(Map<String, String> opts) {
    return Domain.parse(opts.getOrDefault("domain", Domain.FAVORITE.domainName));
  }

  private static Path required(Map<String, String> opts, String name) {
    String v = opts.get(name);
    if (v == null || v.isEmpty()) {
      throw new SyncException("--" + name + " is required");
    }
    return Paths.get(v);
  }

  private static int intOption(Map<String, String> opts, String name, int dflt) {
    String v = opts.get(name);
    if (v == null) {
      return dflt;
    }
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new SyncException("--" + name + " must be an integer, got: " + v);
    }
  }

  static Map<String, String> parse(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      String a = args[i];
      if (!a.startsWith("--")) {
        throw new SyncException("unexpected argument: " + a);
      }
      String key = a.substring(2);
      int eq = key.indexOf('=');
      if (eq >= 0) {
        m.put(key.substring(0, eq), key.substring(eq + 1));
      } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        m.put(key, args[++i]);
      } else {
        m.put(key, "");
      }
    }
    return m;
  }

  private static void usage(PrintStream out) {
    out.println(
        "favorite-sync - backfill / reconcile / rollback tooling for article_favorites,"
            + " comments, tags and articles");
    out.println();
    out.println("  backfill         --source dev.db --target favorite.db [--chunk 5000]");
    out.println("  reverse-backfill --source dev.db --target favorite.db [--chunk 5000]");
    out.println("                   (same as backfill with source/target swapped)");
    out.println("  reconcile        --source dev.db --target favorite.db [--report out.json]");
    out.println("                   [--repair none|to-target|to-source] [--delete-extras]");
    out.println("                   [--authoritative monolith|service] [--max-repair N]");
    out.println();
    out.println(
        "  every command accepts --domain favorite|comment|tag|article (default favorite):");
    out.println("    favorite -> table article_favorites, key (article_id, user_id)");
    out.println("    comment  -> table comments, key id, payload compared for 'diverged'");
    out.println("    tag      -> tables tags (key id, payload name) then");
    out.println("                article_tags (key (article_id, tag_id), no unique constraint)");
    out.println("    article  -> table articles, key id, payload compared for 'diverged';");
    out.println(
        "                slug is UNIQUE: clashes with another id are reported as conflicts");
    out.println("                (tag tables are --domain tag; run tag before article)");
    out.println();
    out.println("exit codes: 0 ok / zero drift, 1 drift remains or backfill conflicts, 2 error");
  }
}
