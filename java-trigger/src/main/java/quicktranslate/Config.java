package quicktranslate;

import javax.swing.KeyStroke;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads ~/.quicktranslate.conf. Creates a default file on first run, then the user
 * edits it (engineer-friendly, no GUI) and restarts the app to change the hotkey.
 */
public class Config {

    public String python;
    public String script;
    public int copyDelayMs;
    public boolean restoreClipboard;

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
            python = /opt/homebrew/bin/python3
            script = /Users/yeqilin/Workspace/macos-quick-translate/translate.py

            # advanced (usually leave as-is)
            copy_delay_ms     = 150
            restore_clipboard = true
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
        return c;
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
