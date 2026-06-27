package quicktranslate;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Tiny append-only logger. Writes timestamped lines to a file kept inside the project
 * (logs/quicktranslate.log) so the user can tail it immediately. It does NOT echo to stdout —
 * launchd captures raw stdout/stderr separately (quicktranslate.out.log) for crash traces, so
 * keeping this file-only avoids every line appearing twice.
 *
 * This exists because the previous build only logged to stdout, which vanishes under a normal GUI
 * launch — a copy/translate failure left no trace and took an hour to diagnose. Every trigger now
 * records the frontmost app, whether the clipboard actually changed, and what got translated.
 *
 * Housekeeping (run once at startup, in {@link #init}):
 *   - if the active log was last written on an earlier day, it is archived to
 *     quicktranslate-YYYY-MM-DD.log,
 *   - archives older than {@value #RETENTION_DAYS} days are deleted,
 * so the log never grows without bound and old logs clean themselves up.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int RETENTION_DAYS = 30;
    private static Path file;

    private Log() {}

    /** Point the log at a file, rotate the old one if stale, and prune month-old archives. */
    public static synchronized void init(Path f) {
        file = f;
        if (f == null) return;
        rotateAndPrune(f);
    }

    public static synchronized void line(String msg) {
        if (file == null) return;
        String stamped = "[" + LocalDateTime.now().format(TS) + "] " + msg;
        try {
            Files.writeString(file, stamped + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[QuickTranslate] log write failed: " + e.getMessage());
        }
    }

    /** A short, single-line preview of copied text for the log (no newlines, capped length). */
    public static String preview(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    private static void rotateAndPrune(Path active) {
        Path dir = active.getParent();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("[QuickTranslate] cannot create log dir (" + dir + "): " + e.getMessage());
            file = null;
            return;
        }

        // rotate: if the active log was last touched on an earlier day, archive it under that date
        try {
            if (Files.exists(active)) {
                LocalDate modified = Files.getLastModifiedTime(active).toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                if (modified.isBefore(LocalDate.now())) {
                    Files.move(active, uniqueArchive(dir, modified));
                }
            }
        } catch (IOException e) {
            System.err.println("[QuickTranslate] log rotate failed: " + e.getMessage());
        }

        // prune: delete dated archives older than the retention window
        LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "quicktranslate-*.log")) {
            for (Path p : ds) {
                try {
                    LocalDate d = Files.getLastModifiedTime(p).toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    if (d.isBefore(cutoff)) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // skip a file we can't stat/delete; try the rest
                }
            }
        } catch (IOException e) {
            System.err.println("[QuickTranslate] log prune failed: " + e.getMessage());
        }
    }

    /** quicktranslate-<date>.log, with a numeric suffix if that name is already taken. */
    private static Path uniqueArchive(Path dir, LocalDate date) {
        Path base = dir.resolve("quicktranslate-" + date + ".log");
        if (!Files.exists(base)) return base;
        for (int i = 1; ; i++) {
            Path p = dir.resolve("quicktranslate-" + date + "." + i + ".log");
            if (!Files.exists(p)) return p;
        }
    }
}
