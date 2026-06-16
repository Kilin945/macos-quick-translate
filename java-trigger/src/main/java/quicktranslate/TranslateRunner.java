package quicktranslate;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On trigger: copy the current selection, read it, optionally restore the previous clipboard,
 * then hand the text to the existing translate.py via TRANSLATE_INPUT. translate.py shows its
 * own dialog/notification, so this class never touches the UI.
 *
 * Copy is performed by the bundled native `copykey` helper, which posts a Cmd+C whose Command
 * flag is bound directly to the C key event (via a private CGEvent source). The old approach
 * (java.awt.Robot pressing META and C as SEPARATE events) raced with the user's still-held
 * physical Command from the hotkey: the modifier intermittently failed to ride with C, so the
 * synthetic keystroke degraded to a bare "C" that copied nothing AND leaked into the active
 * input method (stray Bopomofo/pinyin). copykey eliminates that race; Robot remains only as a
 * fallback if the helper is unavailable.
 *
 * Clipboard I/O goes through native pbcopy/pbpaste (short-lived processes) rather than
 * java.awt.Clipboard, so this long-lived JVM never lingers as the macOS pasteboard owner.
 */
public class TranslateRunner {

    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Robot robot;
    private final String copyHelper; // path to the bundled `copykey` binary, or null if absent

    public TranslateRunner(Config config) {
        this.config = config;
        try {
            robot = new Robot();
        } catch (Exception e) {
            System.err.println("[QuickTranslate] Robot init failed (grant Accessibility?): " + e.getMessage());
        }
        copyHelper = resolveCopyHelper();
    }

    /** Locate the bundled `copykey` helper (sits next to the jpackage launcher). */
    private static String resolveCopyHelper() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path p = Paths.get(appPath).getParent().resolve("copykey");
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    public void trigger() {
        // ignore re-presses while a translation is in flight
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::run, "translate-runner").start();
    }

    private void run() {
        try {
            String saved = readClipboard();

            // clear first so leftover clipboard content can't be mistaken for a fresh selection:
            // if the copy grabs nothing, the clipboard stays blank -> "no selection"
            setClipboard("");
            copySelection();

            // poll (instead of a fixed sleep) so we read the selection as soon as the copy lands
            String selected = waitForClipboard();
            boolean gotSelection = selected != null && !selected.isBlank();

            // Restore the previous clipboard when the user asked for it, OR when nothing was
            // copied (never leave the clipboard wiped just because the hotkey was pressed with no
            // selection). Guard: only restore if the clipboard still holds exactly what we copied,
            // so we never clobber a copy the user made in the meantime.
            if (config.restoreClipboard || !gotSelection) {
                String now = readClipboard();
                if (now != null && now.equals(selected != null ? selected : "")) {
                    setClipboard(saved != null ? saved : "");
                }
            }

            if (!gotSelection) {
                System.out.println("[QuickTranslate] no text selected (clipboard empty after copy)");
                return;
            }
            runTranslate(selected);
        } catch (Exception e) {
            System.err.println("[QuickTranslate] error: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * Wait for the async copy to populate the clipboard, polling in small steps and returning as
     * soon as content appears. Caps at ~max(copyDelayMs, 300ms); if nothing shows up the
     * selection was empty and we return "" (treated as "no selection").
     */
    private String waitForClipboard() throws InterruptedException {
        int cap = Math.max(config.copyDelayMs, 300);
        int waited = 0;
        String s = readClipboard();
        while ((s == null || s.isEmpty()) && waited < cap) {
            Thread.sleep(15);
            waited += 15;
            s = readClipboard();
        }
        return s;
    }

    /**
     * Trigger a Copy. Prefer the native `copykey` helper (binds Command to the C keystroke, so it
     * never degrades to a bare C). Fall back to java.awt.Robot only if the helper is missing or
     * errors — that path has the cmd-drop race this fix is about, so it's a last resort.
     */
    private void copySelection() {
        if (copyHelper != null) {
            try {
                new ProcessBuilder(copyHelper).start().waitFor();
                return;
            } catch (Exception e) {
                System.err.println("[QuickTranslate] copykey failed, using Robot fallback: " + e.getMessage());
            }
        }
        copyViaRobot();
    }

    private void copyViaRobot() {
        if (robot == null) return;
        robot.keyPress(KeyEvent.VK_META);
        robot.keyPress(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_META);
    }

    /** Read the clipboard via `pbpaste` (a short-lived process; no lingering AWT ownership). */
    private String readClipboard() {
        try {
            Process p = new ProcessBuilder("/usr/bin/pbpaste").start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Write the clipboard via `pbcopy`; it writes the real bytes and exits, so the JVM never
     *  becomes the pasteboard owner. */
    private void setClipboard(String s) {
        try {
            Process p = new ProcessBuilder("/usr/bin/pbcopy").start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(s.getBytes(StandardCharsets.UTF_8));
            }
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private void runTranslate(String text) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(config.python, config.script);
        pb.environment().put("TRANSLATE_INPUT", text);
        pb.inheritIO(); // surface translate.py stdout/stderr for debugging
        pb.start();     // don't wait: translate.py shows its own dialog
    }
}
