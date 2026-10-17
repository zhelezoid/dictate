#!/bin/zsh
# Сборка Dictate.app → ~/Applications/Dictate.app и (пере)запуск через launchd (автоперезапуск после падений)
set -euo pipefail
cd "$(dirname "$0")"

APP=build/Dictate.app
IDENTITY="${DICTATE_SIGN_IDENTITY:-Apple Development}"
LABEL=app.dictate
AGENT=~/Library/LaunchAgents/$LABEL.plist
DOMAIN=gui/$(id -u)

rm -rf build
mkdir -p "$APP/Contents/MacOS"

swiftc -O -swift-version 5 -target arm64-apple-macos14 \
  main.swift -o "$APP/Contents/MacOS/Dictate"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.dictate</string>
  <key>CFBundleName</key><string>Dictate</string>
  <key>CFBundleExecutable</key><string>Dictate</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSMicrophoneUsageDescription</key><string>Dictate записывает голос, пока зажат hotkey, и превращает его в текст.</string>
</dict>
</plist>
PLIST

# Стабильная подпись — чтобы разрешения Microphone/Accessibility не слетали после пересборки.
# Нет сертификата Apple Development (Xcode → Settings → Accounts, бесплатный Apple ID) — ad-hoc: работает,
# но разрешения придётся выдавать заново после каждой пересборки
if ! security find-identity -v -p codesigning | grep -q "$IDENTITY"; then
  echo "⚠️ Нет сертификата «$IDENTITY» — подпись ad-hoc (разрешения слетят при пересборке)"
  IDENTITY=-
fi
codesign --force --sign "$IDENTITY" "$APP"

# Сначала выгрузить агент: иначе KeepAlive поднимет старый бинарь посреди копирования
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
pkill -x Dictate 2>/dev/null || true
sleep 1

mkdir -p ~/Applications
rm -rf ~/Applications/Dictate.app
cp -R "$APP" ~/Applications/
sed "s|__HOME__|$HOME|g" launchd/$LABEL.plist > "$AGENT"
launchctl bootstrap "$DOMAIN" "$AGENT"
echo "Installed: ~/Applications/Dictate.app (launchd: $LABEL)"
