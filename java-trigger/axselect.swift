import AppKit
import ApplicationServices

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

func fail(_ msg: String) -> Never {
    FileHandle.standardError.write("axselect: \(msg)\n".data(using: .utf8)!)
    exit(1)
}

// Unambiguous diagnosis first: after every rebuild the app's ad-hoc signature changes, and the
// Accessibility toggle in System Settings can show ON while the grant no longer matches this
// binary — the fix is toggling QuickTranslate OFF and back ON there.
if !AXIsProcessTrusted() {
    fail("not trusted — toggle Accessibility OFF/ON for QuickTranslate")
}

func copyElement(_ parent: AXUIElement, _ attr: String) -> (AXUIElement?, AXError) {
    var ref: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(parent, attr as CFString, &ref)
    guard err == .success, let r = ref, CFGetTypeID(r) == AXUIElementGetTypeID() else { return (nil, err) }
    return ((r as! AXUIElement), err)
}

// Preferred: the system-wide focused element. Known to fail with -25204 on some setups, so fall
// back to asking the frontmost application directly.
let (sysFocused, sysErr) = copyElement(AXUIElementCreateSystemWide(), kAXFocusedUIElementAttribute)

var focused = sysFocused
if focused == nil {
    guard let front = NSWorkspace.shared.frontmostApplication else {
        fail("systemwide err \(sysErr.rawValue); no frontmost app")
    }
    let appEl = AXUIElementCreateApplication(front.processIdentifier)
    let (appFocused, appErr) = copyElement(appEl, kAXFocusedUIElementAttribute)
    if appFocused == nil {
        fail("systemwide err \(sysErr.rawValue), app(\(front.bundleIdentifier ?? "?")) err \(appErr.rawValue)")
    }
    focused = appFocused
}

var selRef: CFTypeRef?
let selErr = AXUIElementCopyAttributeValue(focused!, kAXSelectedTextAttribute as CFString, &selRef)
guard selErr == .success, let sel = selRef as? String else {
    // The focused element has no AXSelectedText (or none right now) — that is a normal
    // "no selection", not an error.
    exit(0)
}
print(sel, terminator: "")
