import Foundation
import Translation

// Offline fallback translation for QuickTranslate: runs Apple's on-device Translation framework
// headlessly (no SwiftUI). Requires macOS 26 (Tahoe) — the first OS where TranslationSession can
// be created directly via installedSource:target:, instead of only through the .translationTask
// SwiftUI view modifier that every earlier version required.
//
// Threading note: the main thread must stay parked in dispatchMain() (NOT blocked on a
// semaphore) — the framework delivers its XPC replies via the main queue, and blocking it
// deadlocks the whole process. exit() is called from inside the Task.
//
// Usage: translatenative <source-lang> <target-lang>   (BCP-47 codes, e.g. en / zh-Hant)
// Reads the text to translate from stdin (UTF-8), writes the translation to stdout.
// Exit codes: 0 = ok, 1 = language pack not installed for this pair, 2 = other failure,
// 3 = watchdog timeout (framework never answered).

func fail(_ code: Int32, _ msg: String) -> Never {
    FileHandle.standardError.write("translatenative: \(msg)\n".data(using: .utf8)!)
    exit(code)
}

guard #available(macOS 26.0, *) else {
    fail(2, "requires macOS 26 (Tahoe) or later")
}

guard CommandLine.arguments.count >= 3 else {
    fail(2, "usage: translatenative <source-lang> <target-lang>  (text on stdin)")
}
let sourceCode = CommandLine.arguments[1]
let targetCode = CommandLine.arguments[2]

let inputData = FileHandle.standardInput.readDataToEndOfFile()
guard let text = String(data: inputData, encoding: .utf8),
      !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
    exit(0)
}

let source = Locale.Language(identifier: sourceCode)
let target = Locale.Language(identifier: targetCode)

// Watchdog: if the framework hangs (e.g. waiting on a consent/download UI that a headless
// process can never show), give up instead of wedging the caller.
DispatchQueue.global().asyncAfter(deadline: .now() + 30) {
    fail(3, "timeout after 30s")
}

Task {
    let availability = LanguageAvailability()
    let status = await availability.status(from: source, to: target)
    guard status == .installed else {
        fail(1, "language pack not installed for \(sourceCode)->\(targetCode) (status: \(status)); "
            + "download it in System Settings > General > Language & Region > Translation Languages")
    }
    do {
        let session = TranslationSession(installedSource: source, target: target)
        let response = try await session.translate(text)
        print(response.targetText)
        exit(0)
    } catch {
        fail(2, "\(error)")
    }
}

dispatchMain()
