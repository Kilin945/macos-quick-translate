package quicktranslate;

import com.tulskiy.keymaster.common.Provider;

import java.nio.file.Path;
import java.nio.file.Paths;
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

        Log.init(config.logFile);
        Log.line("=== QuickTranslate starting ===");
        Log.line("hotkey = " + config.hotkeyDisplay());
        Log.line("python = " + config.python);
        Log.line("script = " + config.script);
        Log.line("log    = " + config.logFile);

        // refuse to run a second copy (e.g. a leftover Login Item alongside the launchd agent),
        // which would register the hotkey twice and double-fire every translation
        Path parent = config.logFile.getParent();
        Path lockFile = (parent != null ? parent : Paths.get(System.getProperty("java.io.tmpdir")))
                .resolve("quicktranslate.lock");
        if (!SingleInstance.acquire(lockFile)) {
            Log.line("another instance is already running — exiting");
            return;
        }

        // register the hotkey AT THE OS LEVEL (macOS Carbon): the keystroke is consumed by
        // the system and never reaches the focused app, so there is no "unhandled shortcut" beep
        Provider provider = Provider.getCurrentProvider(false);
        TranslateRunner runner = new TranslateRunner(config);
        provider.register(config.keyStroke, hotKey -> {
            // the listener thread must survive anything a trigger throws
            try {
                runner.trigger();
            } catch (Throwable t) {
                Log.line("ERROR dispatching trigger: " + t);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            provider.reset();
            provider.stop();
        }));

        Log.line("running — select text anywhere and press " + config.hotkeyDisplay() + ".");

        // keep the JVM alive; the Carbon provider runs on its own thread
        new CountDownLatch(1).await();
    }
}
