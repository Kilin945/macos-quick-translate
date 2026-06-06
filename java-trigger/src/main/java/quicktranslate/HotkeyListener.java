package quicktranslate;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

/**
 * Fires the runner only on an EXACT modifier match, so cmd+' does not also trigger
 * on cmd+shift+' etc.
 */
public class HotkeyListener implements NativeKeyListener {

    private final Config config;
    private final TranslateRunner runner;

    public HotkeyListener(Config config) {
        this.config = config;
        this.runner = new TranslateRunner(config);
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (e.getKeyCode() != config.keyCode) return;

        int m = e.getModifiers();
        boolean cmd = (m & NativeKeyEvent.META_MASK) != 0;
        boolean shift = (m & NativeKeyEvent.SHIFT_MASK) != 0;
        boolean ctrl = (m & NativeKeyEvent.CTRL_MASK) != 0;
        boolean alt = (m & NativeKeyEvent.ALT_MASK) != 0;

        if (cmd == config.cmd && shift == config.shift && ctrl == config.ctrl && alt == config.alt) {
            runner.trigger();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
    }
}
