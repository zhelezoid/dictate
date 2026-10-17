#!/bin/bash
# Send personal data to the phone / pull history (USB debugging on, phone connected).
#   ./push-data.sh            config.json, memory.md, crm.md (whichever exist) → phone
#   ./push-data.sh pull       history.jsonl from the phone → here (private! delete after analysis)
# App package: app.dictate. Files live in the app private folder (files/).
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
command -v adb >/dev/null && ADB=adb
PKG=app.dictate
if [[ "${1:-}" == "pull" ]]; then
  "$ADB" exec-out run-as $PKG cat files/history.jsonl > history.jsonl
  chmod 600 history.jsonl
  echo "history.jsonl: $(wc -l < history.jsonl) lines"
  exit
fi
for f in config.json memory.md crm.md; do
  [[ -f "$f" ]] || continue
  if [[ "$f" == config.json ]]; then python3 -m json.tool "$f" >/dev/null || { echo "config.json: invalid JSON"; exit 1; }; fi
  "$ADB" shell "run-as $PKG sh -c 'cat > files/$f.tmp && mv files/$f.tmp files/$f && chmod 600 files/$f'" < "$f"
  echo "→ $f"
done
