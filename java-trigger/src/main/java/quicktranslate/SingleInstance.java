package quicktranslate;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Whole-process singleton guard. Holds an exclusive lock on a lock file for the JVM's lifetime
 * (the OS releases it automatically when the process dies). Prevents two QuickTranslate instances
 * from both registering the global hotkey — which would double-fire every translation — if, say,
 * both a leftover Login Item and the launchd agent start it.
 *
 * Fails open: if anything goes wrong acquiring the lock we let the app start rather than block it.
 */
public final class SingleInstance {

    @SuppressWarnings("unused") // kept to pin the lock/channel for the JVM's lifetime
    private static FileChannel channel;
    @SuppressWarnings("unused")
    private static FileLock lock;

    private SingleInstance() {}

    /** @return true if we now hold the lock (we're the only instance); false if another holds it. */
    public static boolean acquire(Path lockFile) {
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            return lock != null;
        } catch (Exception e) {
            System.err.println("[QuickTranslate] single-instance check failed, continuing: " + e.getMessage());
            return true;
        }
    }
}
