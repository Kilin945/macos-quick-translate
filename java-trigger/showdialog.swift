import AppKit

// Native translation-result window for QuickTranslate, replacing `osascript display dialog`.
//
// Why native: an osascript dialog spawned from this launchd background chain has no proper GUI
// session identity; when the user's input method (IMK) attaches to it on focus, the mach port
// handshake can fail ("error messaging the mach port for IMKCFRunLoopWakeUpReliable") and wedge
// osascript's run loop forever — window visible, clicks dead. A real NSApplication run loop
// gives IMK the normal attachment path, and makes every close path below reliable.
//
// Usage: showdialog <window-title>   (text on stdin, UTF-8)
// Prints nothing. Exit 0 = window shown and closed (or empty input), non-zero = startup failure.
//
// Close paths: Close button / title-bar red button / Return, Esc, clicking anywhere outside
// the window, losing focus after holding it for 2s, and an orphan sweep — every 120s, a window
// that is NOT key (nobody is looking at an unfocused window) closes itself, so a wedged or
// abandoned parent can never leave a permanent window on screen. A focused window is never
// swept, however long the user reads.
//
// The 2s hold requirement on the focus-loss path matters: a background process activating
// itself can have focus bounced straight back (cooperative activation, or the user mid-
// keystroke in another app), and closing on that bounce would kill the window before anyone
// reads it. The outside-click monitor is the primary "back to work" dismissal and is immune
// to focus bounces entirely.

func fail(_ msg: String) -> Never {
    FileHandle.standardError.write("showdialog: \(msg)\n".data(using: .utf8)!)
    exit(2)
}

let dialogTitle = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Google Translate"

let inputData = FileHandle.standardInput.readDataToEndOfFile()
guard let rawText = String(data: inputData, encoding: .utf8) else {
    fail("stdin is not valid UTF-8")
}
let bodyText = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
if bodyText.isEmpty {
    exit(0)
}

final class DialogDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var window: NSWindow!
    var keySince: Date?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let contentWidth: CGFloat = 480
        let textInset: CGFloat = 12
        let buttonBarHeight: CGFloat = 46
        let font = NSFont.systemFont(ofSize: 14)

        // measure the text at the fixed width to size the window to its content,
        // capped at 70% of the screen so long texts scroll instead of overflowing
        let textWidth = contentWidth - textInset * 2
        let measured = NSAttributedString(string: bodyText, attributes: [.font: font])
            .boundingRect(with: NSSize(width: textWidth, height: .greatestFiniteMagnitude),
                          options: [.usesLineFragmentOrigin, .usesFontLeading])
        let maxTextHeight = (NSScreen.main?.visibleFrame.height ?? 800) * 0.7
        let textHeight = min(ceil(measured.height) + textInset * 2, maxTextHeight)
        let contentHeight = max(textHeight, 60) + buttonBarHeight

        let content = NSView(frame: NSRect(x: 0, y: 0, width: contentWidth, height: contentHeight))

        let textView = NSTextView(frame: NSRect(x: 0, y: 0, width: textWidth, height: 0))
        textView.isEditable = false
        textView.isSelectable = true
        textView.font = font
        textView.string = bodyText
        textView.textContainerInset = NSSize(width: textInset, height: textInset)
        textView.autoresizingMask = [.width]
        textView.isVerticallyResizable = true
        textView.textContainer?.widthTracksTextView = true

        let scroll = NSScrollView(frame: NSRect(x: 0, y: buttonBarHeight,
                                                width: contentWidth,
                                                height: contentHeight - buttonBarHeight))
        scroll.documentView = textView
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        scroll.autoresizingMask = [.width, .height]
        content.addSubview(scroll)

        let closeButton = NSButton(title: "Close", target: NSApp,
                                   action: #selector(NSApplication.terminate(_:)))
        closeButton.bezelStyle = .rounded
        closeButton.keyEquivalent = "\r"
        closeButton.sizeToFit()
        closeButton.setFrameOrigin(NSPoint(
            x: contentWidth - closeButton.frame.width - 14,
            y: (buttonBarHeight - closeButton.frame.height) / 2))
        closeButton.autoresizingMask = [.minXMargin, .maxYMargin]
        content.addSubview(closeButton)

        window = NSWindow(contentRect: content.frame,
                          styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.title = dialogTitle
        window.contentView = content
        window.minSize = NSSize(width: 320, height: 160)
        window.delegate = self
        window.center()
        window.isReleasedWhenClosed = false

        // Esc closes (a titled non-panel window has no built-in Esc binding)
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode == 53 {
                NSApp.terminate(nil)
                return nil
            }
            return event
        }

        // clicking anywhere outside the window closes it — the primary "back to work"
        // gesture, and unlike the focus-loss path it cannot misfire on a focus bounce
        NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { _ in
            NSApp.terminate(nil)
        }

        // orphan sweep: an unfocused window with no owner will never receive a close click;
        // a focused one is being read — leave it alone and check again later
        Timer.scheduledTimer(withTimeInterval: 120, repeats: true) { [weak self] _ in
            if self?.window?.isKeyWindow != true {
                NSApp.terminate(nil)
            }
        }

        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    func windowDidBecomeKey(_ notification: Notification) {
        keySince = Date()
    }

    // close on losing focus — but only after holding it ≥2s, so a focus bounce right
    // after our self-activation can't slam the window shut before anyone reads it
    func windowDidResignKey(_ notification: Notification) {
        if let since = keySince, Date().timeIntervalSince(since) >= 2 {
            NSApp.terminate(nil)
        }
    }

    func windowWillClose(_ notification: Notification) {
        NSApp.terminate(nil)
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = DialogDelegate()
app.delegate = delegate
app.run()
