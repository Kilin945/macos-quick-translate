package quicktranslate;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void main(String[] args) {
        // JNativeHook logs every key at INFO by default — silence it
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.WARNING);
        logger.setUseParentHandlers(false);

        Config config;
        try {
            config = Config.load();
        } catch (Exception e) {
            System.err.println("[QuickTranslate] bad config: " + e.getMessage());
            return;
        }

        System.out.println("[QuickTranslate] hotkey = " + config.hotkeyDisplay());
        System.out.println("[QuickTranslate] python = " + config.python);
        System.out.println("[QuickTranslate] script = " + config.script);

        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException e) {
            System.err.println("[QuickTranslate] failed to register global hook: " + e.getMessage());
            System.err.println("On macOS: System Settings > Privacy & Security > Accessibility "
                    + "-> enable this app (or your terminal/IDE when running via gradle).");
            return;
        }

        GlobalScreen.addNativeKeyListener(new HotkeyListener(config));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                GlobalScreen.unregisterNativeHook();
            } catch (NativeHookException ignored) {
            }
        }));

        System.out.println("[QuickTranslate] running — select text anywhere and press "
                + config.hotkeyDisplay() + ". Ctrl+C to quit.");
    }
}
