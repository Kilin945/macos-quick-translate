#!/usr/bin/env bash
# Build and install the QuickTranslate global-hotkey app: app bundle -> /Applications,
# config + LaunchAgent generated from the .sample templates, then loaded via launchd.
# Usage: scripts/install.sh [--no-build]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="com.quicktranslate"
APP_SRC="$REPO_ROOT/java-trigger/build/jpackage/QuickTranslate.app"
APP_DST="/Applications/QuickTranslate.app"
CONF="$HOME/.quicktranslate.conf"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
UID_NUM="$(id -u)"

if [[ "${1:-}" != "--no-build" ]]; then
    echo "==> Building app"
    "$REPO_ROOT/java-trigger/build_app.sh"
fi
[[ -d "$APP_SRC" ]] || { echo "App not built: $APP_SRC (run without --no-build)" >&2; exit 1; }

echo "==> Stopping any running agent"
launchctl bootout "gui/$UID_NUM/$LABEL" 2>/dev/null || true

echo "==> Installing $APP_DST"
rm -rf "$APP_DST"
cp -R "$APP_SRC" "$APP_DST"

echo "==> Ensuring logs dir"
mkdir -p "$REPO_ROOT/logs"

if [[ ! -f "$CONF" ]]; then
    echo "==> Seeding $CONF"
    sed "s#__PROJECT_DIR__#$REPO_ROOT#g" "$REPO_ROOT/scripts/quicktranslate.conf.sample" > "$CONF"
else
    echo "==> Keeping existing $CONF"
fi

echo "==> Writing LaunchAgent $PLIST"
mkdir -p "$HOME/Library/LaunchAgents"
sed "s#__PROJECT_DIR__#$REPO_ROOT#g" "$REPO_ROOT/scripts/com.quicktranslate.plist.sample" > "$PLIST"

echo "==> Loading agent"
# bootout is async: wait for the old registration to fully clear, else bootstrap hits EIO
for _ in $(seq 1 10); do
    launchctl print "gui/$UID_NUM/$LABEL" >/dev/null 2>&1 || break
    sleep 0.5
done
launchctl bootstrap "gui/$UID_NUM" "$PLIST"
launchctl kickstart "gui/$UID_NUM/$LABEL" 2>/dev/null || true

echo
echo "Installed and running. Opening the Accessibility settings — just turn ON 'QuickTranslate'."
echo "(That permission lets it send Cmd+C in normal apps; terminals don't need it.)"
echo "Config: $CONF   |   Logs: $REPO_ROOT/logs/quicktranslate.log"
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" 2>/dev/null || true
