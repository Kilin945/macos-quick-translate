import ApplicationServices
import Foundation

// Print the focused element's AXSelectedText for QuickTranslate (prints nothing when there is
// no selection).
//
// Why not osascript + System Events: that path needs the separate Automation (Apple Events)
// consent, and macOS never shows the consent prompt for an ad-hoc-signed launchd agent — the
// event is silently denied with -1743, and even a granted consent would die on the next build
// because the ad-hoc signature changes. Calling the Accessibility C API directly needs only the
// Accessibility grant the app already holds for copykey/global-hotkey, attributed to us as the
// parent process.
//
// Exit codes: 0 = read OK (stdout holds the selection, possibly empty), 1 = AX failure
// (untrusted / no focused element), so the caller can tell "no selection" from "broken".

let sys = AXUIElementCreateSystemWide()

var focusedRef: CFTypeRef?
let focusedErr = AXUIElementCopyAttributeValue(sys, kAXFocusedUIElementAttribute as CFString, &focusedRef)
guard focusedErr == .success, let ref = focusedRef, CFGetTypeID(ref) == AXUIElementGetTypeID() else {
    FileHandle.standardError.write("axselect: no focused element (AXError \(focusedErr.rawValue))\n".data(using: .utf8)!)
    exit(1)
}
let focused = ref as! AXUIElement

var selRef: CFTypeRef?
let selErr = AXUIElementCopyAttributeValue(focused, kAXSelectedTextAttribute as CFString, &selRef)
guard selErr == .success, let sel = selRef as? String else {
    // The focused element has no AXSelectedText (or none right now) — that is a normal
    // "no selection", not an error.
    exit(0)
}
print(sel, terminator: "")
