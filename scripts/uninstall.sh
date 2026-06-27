#!/usr/bin/env bash
# Remove the QuickTranslate global-hotkey app and its LaunchAgent.
# Leaves ~/.quicktranslate.conf and logs/ in place. Usage: scripts/uninstall.sh
set -euo pipefail

LABEL="com.quicktranslate"
UID_NUM="$(id -u)"

echo "==> Stopping & removing agent"
launchctl bootout "gui/$UID_NUM/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"

echo "==> Removing /Applications/QuickTranslate.app"
rm -rf "/Applications/QuickTranslate.app"

echo "Done. Left in place: ~/.quicktranslate.conf and the repo's logs/ dir."
