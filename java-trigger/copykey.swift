import Foundation
import CoreGraphics

// Reliable synthetic Cmd+C for QuickTranslate.
//
// The old approach (java.awt.Robot: press META, press C, release C, release META) sends the
// Command modifier as a SEPARATE key event from C. When the user is still holding the hotkey's
// physical Command, the synthetic modifier events tangle with the hardware state and get
// reordered, so the C intermittently lands WITHOUT Command — a bare "C" that copies nothing and
// leaks into the active input method (stray Bopomofo/pinyin). Confirmed via event-tap logs.
//
// Fix: (1) briefly wait for the user's physical Command to lift, then (2) post C with the
// Command flag bound DIRECTLY to the C key events, using a PRIVATE event source so the posted
// flags are authoritative and are NOT merged with the live hardware modifier state. Command is
// therefore guaranteed to ride with this C keystroke.

// (1) insurance: wait up to ~300ms for the physical Command key to be released
let deadline = Date().addingTimeInterval(0.3)
while Date() < deadline {
    if !CGEventSource.flagsState(.hidSystemState).contains(.maskCommand) { break }
    usleep(10_000) // 10ms
}

// (2) post a self-contained Cmd+C
let src = CGEventSource(stateID: .privateState)
let kVK_ANSI_C: CGKeyCode = 8
guard let down = CGEvent(keyboardEventSource: src, virtualKey: kVK_ANSI_C, keyDown: true),
      let up   = CGEvent(keyboardEventSource: src, virtualKey: kVK_ANSI_C, keyDown: false) else {
    FileHandle.standardError.write("copykey: failed to create key events\n".data(using: .utf8)!)
    exit(1)
}
down.flags = .maskCommand
up.flags = .maskCommand
down.post(tap: .cghidEventTap)
usleep(5_000)
up.post(tap: .cghidEventTap)
usleep(20_000) // let the event flush before we exit
