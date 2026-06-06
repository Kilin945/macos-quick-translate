package quicktranslate;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    // parsed hotkey
    public int keyCode;
    public boolean cmd, shift, ctrl, alt;
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
        cmd = shift = ctrl = alt = false;
        String keyPart = null;
        for (String raw : s.split("\\+")) {
            String t = raw.trim().toLowerCase();
            switch (t) {
                case "cmd", "command", "meta" -> cmd = true;
                case "shift" -> shift = true;
                case "ctrl", "control" -> ctrl = true;
                case "alt", "option", "opt" -> alt = true;
                default -> keyPart = raw.trim();
            }
        }
        if (keyPart == null || keyPart.isEmpty()) {
            throw new IllegalArgumentException("hotkey has no main key: " + s);
        }
        keyCode = parseKey(keyPart);
    }

    /** Map a single key string to a JNativeHook VC_* code via reflection on the field name. */
    private static int parseKey(String k) {
        String field;
        if (k.length() == 1) {
            char c = Character.toUpperCase(k.charAt(0));
            field = switch (c) {
                case '\'' -> "VC_QUOTE";
                case ';' -> "VC_SEMICOLON";
                case ',' -> "VC_COMMA";
                case '.' -> "VC_PERIOD";
                case '/' -> "VC_SLASH";
                case '\\' -> "VC_BACK_SLASH";
                case '[' -> "VC_OPEN_BRACKET";
                case ']' -> "VC_CLOSE_BRACKET";
                case '-' -> "VC_MINUS";
                case '=' -> "VC_EQUALS";
                case '`' -> "VC_BACKQUOTE";
                default -> "VC_" + c; // letters A-Z and digits 0-9
            };
        } else {
            field = "VC_" + k.toUpperCase(); // SPACE, ENTER, TAB, F1..F12, etc.
        }
        try {
            return NativeKeyEvent.class.getField(field).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown hotkey key: '" + k + "' (resolved to " + field + ")");
        }
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
