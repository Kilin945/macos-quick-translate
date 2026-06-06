package quicktranslate;

import com.tulskiy.keymaster.common.Provider;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        // quiet jkeymaster's internal logging
        Logger.getLogger("com.tulskiy.keymaster").setLevel(Level.WARNING);

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

        // register the hotkey AT THE OS LEVEL (macOS Carbon): the keystroke is consumed by
        // the system and never reaches the focused app, so there is no "unhandled shortcut" beep
        Provider provider = Provider.getCurrentProvider(false);
        TranslateRunner runner = new TranslateRunner(config);
        provider.register(config.keyStroke, hotKey -> runner.trigger());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            provider.reset();
            provider.stop();
        }));

        System.out.println("[QuickTranslate] running — select text anywhere and press "
                + config.hotkeyDisplay() + ".");

        // keep the JVM alive; the Carbon provider runs on its own thread
        new CountDownLatch(1).await();
    }
}
