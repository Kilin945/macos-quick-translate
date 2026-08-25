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
// Prints nothing. Exit 0 = window shown and closed (or empty input, or replaced by a newer
// window), non-zero = startup failure.
//
// The window floats above all normal windows (.floating level) so the user can page/scroll the
// document behind it and compare against the translation — which is also why interacting with
// other apps must NOT dismiss it. Close paths: Close button / title-bar red button / Return,
// Esc (when the window has focus), a newer showdialog instance replacing this one, and an
// orphan check — if the parent (translate.py, which blocks on us) dies, we are reparented to
// launchd (ppid 1) and close ourselves, so an abandoned window can never stay on screen forever.

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

// single window: a new translation replaces the previous window instead of stacking floating
// windows. SIGTERM is the replace signal — see the handler below for why it exits 0.
func killOlderInstances() {
    let pgrep = Process()
    pgrep.executableURL = URL(fileURLWithPath: "/usr/bin/pgrep")
    pgrep.arguments = ["-x", "showdialog"]
    let pipe = Pipe()
    pgrep.standardOutput = pipe
    guard (try? pgrep.run()) != nil else { return }
    pgrep.waitUntilExit()
    let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
    for line in out.split(separator: "\n") {
        if let pid = Int32(line.trimmingCharacters(in: .whitespaces)), pid != getpid() {
            kill(pid, SIGTERM)
        }
    }
}

// being replaced (SIGTERM from a newer instance) is a normal end, not a failure — exit 0 so
// translate.py does not mistake it for a dialog error and fire the notification fallback
signal(SIGTERM, SIG_IGN)
let sigTermSource = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
sigTermSource.setEventHandler { exit(0) }
sigTermSource.resume()

final class DialogDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var window: NSWindow!

    func applicationDidFinishLaunching(_ notification: Notification) {
        killOlderInstances()

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
        // stay above normal windows so the user can page the document behind and compare
        window.level = .floating

        // Esc closes (a titled non-panel window has no built-in Esc binding); only reaches
        // us while the window has focus — Esc pressed in other apps stays theirs
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode == 53 {
                NSApp.terminate(nil)
                return nil
            }
            return event
        }

        // orphan check: translate.py blocks on this process for the window's whole life, so
        // a parent death (reparent to launchd, ppid 1) means we were abandoned — close
        Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in
            if getppid() == 1 {
                NSApp.terminate(nil)
            }
        }

        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
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
