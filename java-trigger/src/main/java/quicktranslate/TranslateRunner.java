package quicktranslate;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On trigger: simulate cmd+C to grab the current selection (works in EVERY app, unlike
 * macOS Services), read it from the clipboard, optionally restore the old clipboard, then
 * hand the text to the existing translate.py via TRANSLATE_INPUT. translate.py shows its
 * own dialog/notification, so this class never touches the UI.
 */
public class TranslateRunner {

    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Robot robot;

    public TranslateRunner(Config config) {
        this.config = config;
        try {
            robot = new Robot();
        } catch (Exception e) {
            System.err.println("[QuickTranslate] Robot init failed (grant Accessibility?): " + e.getMessage());
        }
    }

    public void trigger() {
        // ignore re-presses while a translation is in flight
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::run, "translate-runner").start();
    }

    private void run() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            String saved = config.restoreClipboard ? readClipboard(cb) : null;

            copySelection();
            Thread.sleep(config.copyDelayMs); // copy is async; let the clipboard settle

            String selected = readClipboard(cb);

            if (config.restoreClipboard && saved != null) {
                setClipboard(cb, saved);
            }

            if (selected == null || selected.isBlank()) {
                System.out.println("[QuickTranslate] no text selected");
                return;
            }
            runTranslate(selected);
        } catch (Exception e) {
            System.err.println("[QuickTranslate] error: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void copySelection() {
        if (robot == null) return;
        robot.keyPress(KeyEvent.VK_META);
        robot.keyPress(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_C);
        robot.keyRelease(KeyEvent.VK_META);
    }

    private String readClipboard(Clipboard cb) {
        try {
            Transferable t = cb.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void setClipboard(Clipboard cb, String s) {
        try {
            cb.setContents(new StringSelection(s), null);
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
