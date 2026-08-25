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

echo "==> Compiling native helpers into the bundle (copykey: synthetic Cmd+C, axselect: selected-text read, translatenative: offline translation, showdialog: result window)"
swiftc copykey.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/copykey"
swiftc axselect.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/axselect"
swiftc translatenative.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/translatenative"
swiftc showdialog.swift -O -o "$OUT_DIR/$APP_NAME.app/Contents/MacOS/showdialog"

echo "==> Done: $OUT_DIR/$APP_NAME.app"
