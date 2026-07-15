#!/bin/bash
# Build QuickTranslate.app (self-contained, bundles its own JRE) via jpackage.
# Re-run this after changing the Java code to regenerate the app.
set -e
cd "$(dirname "$0")"

APP_NAME="QuickTranslate"
OUT_DIR="build/jpackage"

JPACKAGE="$(command -v jpackage)"
if [ -z "$JPACKAGE" ]; then
  echo "jpackage not found on PATH. Set JAVA_HOME to a JDK 17+ and retry." >&2
  exit 1
fi

echo "==> Building distribution"
./gradlew installDist --console=plain

echo "==> Cleaning previous app image"
rm -rf "$OUT_DIR"

echo "==> Running jpackage"
"$JPACKAGE" \
  --type app-image \
  --name "$APP_NAME" \
  --input build/install/quick-translate-trigger/lib \
  --main-jar quick-translate-trigger.jar \
  --main-class quicktranslate.Main \
  --dest "$OUT_DIR" \
  --java-options "-Djava.awt.headless=false"

PLIST="$OUT_DIR/$APP_NAME.app/Contents/Info.plist"

echo "==> Marking as background agent (LSUIElement, no Dock icon)"
/usr/libexec/PlistBuddy -c "Set :LSUIElement true" "$PLIST" 2>/dev/null \
  || /usr/libexec/PlistBuddy -c "Add :LSUIElement bool true" "$PLIST"

echo "==> Declaring Apple-events usage (without this macOS silently denies System Events access)"
/usr/libexec/PlistBuddy -c "Set :NSAppleEventsUsageDescription QuickTranslate reads the selected text in terminals (via System Events / AXSelectedText) so the hotkey can translate it." "$PLIST" 2>/dev/null \
  || /usr/libexec/PlistBuddy -c "Add :NSAppleEventsUsageDescription string QuickTranslate reads the selected text in terminals (via System Events / AXSelectedText) so the hotkey can translate it." "$PLIST"

echo "==> Compiling copykey helper into the bundle (reliable synthetic Cmd+C)"
swiftc copykey.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/copykey"

echo "==> Done: $OUT_DIR/$APP_NAME.app"
