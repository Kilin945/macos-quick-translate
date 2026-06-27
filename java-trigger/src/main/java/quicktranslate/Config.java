package quicktranslate;

import javax.swing.KeyStroke;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Loads ~/.quicktranslate.conf. Creates a default file on first run, then the user
 * edits it (engineer-friendly, no GUI) and restarts the app to change the hotkey.
 */
public class Config {

    public String python;
    public String script;
    public int copyDelayMs;
    public boolean restoreClipboard;

    // where to write the runtime log; kept inside the project dir so it's easy to tail
    public Path logFile;

    // bundle ids of terminal apps: in these we DON'T synthesize Cmd+C (it would hit the terminal's
    // disabled Copy menu and beep). The selection is already on the clipboard via the app's own copy.
    public Set<String> terminalApps;

    // parsed hotkey, as a javax.swing.KeyStroke for jkeymaster to register with the OS
    public KeyStroke keyStroke;
    public String hotkeyRaw;

    static final String CONF = System.getProperty("user.home") + "/.quicktranslate.conf";

    static final String DEFAULT_CONTENT = """
            # QuickTranslate config
            # hotkey: combine cmd / shift / ctrl / alt with one key.
            #   examples:  cmd+'   |   cmd+shift+t   |   ctrl+alt+space
            hotkey = cmd+'

            # translation backend (reuses your existing translate.py, unchanged)
            # set `script` to the absolute path of translate.py in your checkout
            python = /opt/homebrew/bin/python3
            script = /path/to/macos-quick-translate/translate.py

            # advanced (usually leave as-is)
            copy_delay_ms     = 150
            # false: after translating, the selected word stays on the clipboard (so you can paste
            # it). true: restore whatever was on the clipboard before translating (but then you
            # can't paste the just-translated word).
            restore_clipboard = false

            # terminal apps (comma-separated bundle ids): here the hotkey does NOT send Cmd+C — the
            # selection is already on the clipboard via the app's own copy (e.g. Claude Code's
            # copy-on-select), and a synthetic Cmd+C would just beep on the terminal's disabled Copy
            # menu. The log prints the frontmost app's id, so add others as needed (iTerm =
            # com.googlecode.iterm2).
            terminal_apps = com.apple.Terminal
            """;

    public static Config load() {
        Path path = Paths.get(CONF);
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_CONTENT);
                System.out.println("[QuickTranslate] created default config at " + CONF);
            }
        } catch (IOException e) {
            System.err.println("[QuickTranslate] could not create config: " + e.getMessage());
        }

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[QuickTranslate] could not read config, using defaults: " + e.getMessage());
        }

        Config c = new Config();
        c.python = p.getProperty("python", "/opt/homebrew/bin/python3").trim();
        c.script = p.getProperty("script", "").trim();
        c.copyDelayMs = parseInt(p.getProperty("copy_delay_ms", "150").trim(), 150);
        c.restoreClipboard = Boolean.parseBoolean(p.getProperty("restore_clipboard", "true").trim());
        c.parseHotkey(p.getProperty("hotkey", "cmd+'").trim());
        c.parseTerminalApps(p.getProperty("terminal_apps", "com.apple.Terminal"));
        c.computeLogFile();
        return c;
    }

    /**
     * Log next to the project (the dir holding translate.py), at logs/quicktranslate.log, so the
     * user can tail it right inside the repo. Falls back to ~/Library/Logs if the script path is
     * unusable.
     */
    private void computeLogFile() {
        try {
            if (script != null && !script.isBlank()) {
                Path parent = Paths.get(script).toAbsolutePath().getParent();
                if (parent != null) {
                    logFile = parent.resolve("logs").resolve("quicktranslate.log");
                    return;
                }
            }
        } catch (Exception ignored) {
            // fall through to the home-dir default
        }
        logFile = Paths.get(System.getProperty("user.home"), "Library", "Logs", "QuickTranslate.log");
    }

    private void parseTerminalApps(String s) {
        terminalApps = new HashSet<>();
        for (String t : s.split(",")) {
            String v = t.trim().toLowerCase();
            if (!v.isEmpty()) terminalApps.add(v);
        }
    }

    /** True if the given frontmost bundle id is a configured terminal (don't synthesize Cmd+C). */
    public boolean isTerminal(String bundleId) {
        return bundleId != null && terminalApps.contains(bundleId.toLowerCase());
    }

    private void parseHotkey(String s) {
        hotkeyRaw = s;
        List<String> mods = new ArrayList<>();
        String keyPart = null;
        for (String raw : s.split("\\+")) {
            String t = raw.trim().toLowerCase();
            switch (t) {
                case "cmd", "command", "meta" -> mods.add("meta");
                case "shift" -> mods.add("shift");
                case "ctrl", "control" -> mods.add("control");
                case "alt", "option", "opt" -> mods.add("alt");
                default -> keyPart = raw.trim();
            }
        }
        if (keyPart == null || keyPart.isEmpty()) {
            throw new IllegalArgumentException("hotkey has no main key: " + s);
        }
        // build a javax.swing.KeyStroke spec, e.g. "meta QUOTE" or "control alt T"
        String spec = (mods.isEmpty() ? "" : String.join(" ", mods) + " ") + keyName(keyPart);
        keyStroke = KeyStroke.getKeyStroke(spec);
        if (keyStroke == null) {
            throw new IllegalArgumentException("could not parse hotkey '" + s + "' (spec: " + spec + ")");
        }
    }

    /** Map a key string to an AWT VK_* name (without the VK_ prefix), as KeyStroke expects. */
    private static String keyName(String k) {
        if (k.length() == 1) {
            char c = Character.toUpperCase(k.charAt(0));
            return switch (c) {
                case '\'' -> "QUOTE";
                case ';' -> "SEMICOLON";
                case ',' -> "COMMA";
                case '.' -> "PERIOD";
                case '/' -> "SLASH";
                case '\\' -> "BACK_SLASH";
                case '[' -> "OPEN_BRACKET";
                case ']' -> "CLOSE_BRACKET";
                case '-' -> "MINUS";
                case '=' -> "EQUALS";
                case '`' -> "BACK_QUOTE";
                default -> String.valueOf(c); // letters A-Z and digits 0-9
            };
        }
        return k.toUpperCase(); // SPACE, ENTER, TAB, F1..F12, etc.
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String hotkeyDisplay() {
        return hotkeyRaw;
    }
}
