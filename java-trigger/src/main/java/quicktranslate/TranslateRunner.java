package quicktranslate;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On trigger: copy the current selection (Cmd+C), then translate whatever ends up on the
 * clipboard. The text is handed to the existing translate.py via TRANSLATE_INPUT; translate.py
 * shows its own dialog/notification, so this class never touches the UI.
 *
 * Design note — why we do NOT clear the clipboard first:
 *   The previous version did setClipboard("") before Cmd+C, using "empty afterwards" to mean
 *   "nothing was selected". That breaks any app that puts the selection on the clipboard itself
 *   (terminals in copy-on-select mode, e.g. Claude Code's TUI): the clear wiped the text the user
 *   had already copied, and the synthetic Cmd+C — which goes to Terminal.app, not the TUI — could
 *   not re-copy it (the selection lives inside the TUI, Terminal has no native selection). Result:
 *   empty clipboard -> "no selection" -> no translation, plus a beep from Cmd+C hitting Terminal's
 *   disabled Copy menu.
 *
 *   New behaviour (works everywhere without per-app special-casing):
 *     1. remember what's on the clipboard,
 *     2. send Cmd+C (so plain "select -> hotkey" still works in normal apps),
 *     3. translate whatever the clipboard holds afterwards.
 *   In a normal app the Cmd+C copies the fresh selection. In a copy-on-select terminal the text is
 *   already on the clipboard (put there at selection time) and we simply use it. Nothing is
 *   destroyed, so no workflow is broken.
 *
 * Terminal special case (see Config.terminalApps): we never synthesize Cmd+C there. First we ask
 * Accessibility for the terminal's native selection (AXSelectedText, via the bundled `axselect`
 * helper) so a plain-shell
 * "select -> hotkey" translates directly; if there is none (TUIs like Claude Code intercept the
 * mouse and copy-on-select instead), we translate whatever is already on the clipboard.
 *
 * Copy is performed by the bundled native `copykey` helper (binds Command directly to the C key
 * event), with java.awt.Robot as a fallback. Clipboard reads go through pbpaste (a short-lived
 * process) so this long-lived JVM never lingers as the macOS pasteboard owner.
 */
public class TranslateRunner {

    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Robot robot;
    private final String copyHelper; // path to the bundled `copykey` binary, or null if absent
    private final String axHelper;   // path to the bundled `axselect` binary, or null if absent

    public TranslateRunner(Config config) {
        this.config = config;
        try {
            robot = new Robot();
        } catch (Exception e) {
            Log.line("Robot init failed (grant Accessibility?): " + e.getMessage());
        }
        copyHelper = resolveHelper("copykey");
        axHelper = resolveHelper("axselect");
        Log.line("copy helper = " + (copyHelper != null ? copyHelper : "(none, using Robot fallback)"));
        Log.line("ax helper   = " + (axHelper != null ? axHelper : "(none, terminal selections use clipboard only)"));
    }

    /** Locate a bundled native helper binary (sits next to the jpackage launcher). */
    private static String resolveHelper(String name) {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path p = Paths.get(appPath).getParent().resolve(name);
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
            String front = frontmostApp();
            boolean terminal = config.isTerminal(front);
            String before = nz(readClipboard());

            String selected;
            boolean changed;
            String ax = "n/a";
            if (terminal) {
                // A synthetic Cmd+C would only hit the terminal's disabled Copy menu and beep, so
                // we never send one here. Instead, prefer the terminal's NATIVE selection read via
                // Accessibility (plain shell: select -> hotkey, no copy needed). TUIs that own the
                // mouse (e.g. Claude Code) leave no native selection — AX reads empty and we fall
                // back to the clipboard their copy-on-select already filled.
                String axSel = axSelectedText();
                ax = (axSel == null) ? "error" : (axSel.isBlank() ? "miss" : "hit");
                if ("hit".equals(ax)) {
                    selected = axSel;
                } else {
                    selected = before;
                }
                changed = false;
            } else {
                // Normal app: send Cmd+C WITHOUT clearing first (see class doc), then wait for the
                // clipboard to change so "select -> hotkey" works without a manual copy.
                copySelection();
                selected = waitForClipboardChange(before);
                changed = !selected.equals(before);
            }

            if (selected.isBlank()) {
                Log.line("no selection (clipboard empty) front=" + front + " terminal=" + terminal
                        + " ax=" + ax);
                return;
            }

            Log.line("translate front=" + front + " terminal=" + terminal + " ax=" + ax
                    + " changed=" + changed
                    + " len=" + selected.length() + " text=\"" + Log.preview(selected) + "\"");
            runTranslate(selected);
        } catch (Throwable t) {
            // never let the worker die in a way that could wedge the listener; just log it
            Log.line("ERROR in run(): " + t);
        } finally {
            running.set(false);
        }
    }

    /**
     * Wait for the clipboard to differ from {@code before} (i.e. for our Cmd+C to actually copy
     * something), polling in small steps. Returns as soon as it changes, or the current contents
     * after a cap of ~max(copyDelayMs, 300)ms if it never changes (the copy-on-select / pre-copy /
     * nothing-selected cases all read whatever is currently there).
     */
    private String waitForClipboardChange(String before) throws InterruptedException {
        int cap = Math.max(config.copyDelayMs, 300);
        int waited = 0;
        String s = nz(readClipboard());
        while (waited < cap && s.equals(before)) {
            Thread.sleep(15);
            waited += 15;
            s = nz(readClipboard());
        }
        return s;
    }

    /**
     * Trigger a Copy. Prefer the native `copykey` helper (binds Command to the C keystroke, so it
     * never degrades to a bare C). Fall back to java.awt.Robot only if the helper is missing.
     */
    private void copySelection() {
        if (copyHelper != null) {
            try {
                new ProcessBuilder(copyHelper).start().waitFor();
                return;
            } catch (Exception e) {
                Log.line("copykey failed, using Robot fallback: " + e.getMessage());
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

    /**
     * Read the focused element's AXSelectedText via the bundled `axselect` helper. In a terminal
     * this is the native mouse selection (plain shell), which lets "select -> hotkey" translate
     * without any copy. TUIs that own the mouse (e.g. Claude Code's mouse-reporting mode) leave
     * the terminal with no native selection, so this reads "" and callers fall back to the
     * clipboard that copy-on-select already filled.
     *
     * The helper talks to the Accessibility C API directly, covered by the app's existing
     * Accessibility grant (same as copykey) — deliberately NOT osascript/System Events, whose
     * separate Automation consent macOS silently refuses to prompt for from this launchd agent.
     *
     * Returns the selected text ("" when nothing is selected), or null on any failure — helper
     * missing, timeout, or AX error — so callers treat failure exactly like "no selection".
     */
    private String axSelectedText() {
        if (axHelper == null) return null;
        try {
            Process p = new ProcessBuilder(axHelper).start();
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.line("axSelectedText timeout");
                return null;
            }
            if (p.exitValue() != 0) {
                // surface WHY in full (helper prints one short diagnostic line, e.g. untrusted)
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                Log.line("axSelectedText exit=" + p.exitValue() + " err=" + err.trim());
                return null;
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.line("axSelectedText error: " + e);
            return null;
        }
    }

    /** Read the clipboard via `pbpaste` (a short-lived process; no lingering AWT ownership). */
    private String readClipboard() {
        try {
            ProcessBuilder pb = new ProcessBuilder("/usr/bin/pbpaste");
            // launchd provides no LANG, and without it pbpaste emits the legacy system
            // encoding (Big5 on zh-TW Macs) — force UTF-8 to match the decode below
            pb.environment().put("LANG", "en_US.UTF-8");
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Best-effort frontmost-app bundle id, for the log only (helps decide later whether a given
     * app needs special handling). Uses the built-in `lsappinfo` — no special permission required.
     * Returns "?" on any failure.
     */
    private String frontmostApp() {
        try {
            String asn = capture("/usr/bin/lsappinfo", "front").trim();
            if (asn.isEmpty()) return "?";
            String info = capture("/usr/bin/lsappinfo", "info", "-only", "bundleid", asn).trim();
            return parseBundleId(info);
        } catch (Exception e) {
            return "?";
        }
    }

    /** lsappinfo prints e.g. {@code "CFBundleIdentifier"="com.apple.Terminal"} — pull the value out. */
    private static String parseBundleId(String info) {
        int eq = info.lastIndexOf('=');
        String v = (eq >= 0 ? info.substring(eq + 1) : info).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? "?" : v;
    }

    private String capture(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out, StandardCharsets.UTF_8);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void runTranslate(String text) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(config.python, config.script);
        pb.environment().put("TRANSLATE_INPUT", text);
        pb.inheritIO(); // surface translate.py stdout/stderr for debugging
        pb.start();     // don't wait: translate.py shows its own dialog
    }
}
