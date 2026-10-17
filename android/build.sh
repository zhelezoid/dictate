#!/bin/zsh
# Сборка без Gradle — инструментами SDK напрямую (как dictate на Mac: один скрипт, без зависимостей).
# ./build.sh          — собрать build/Dictate.apk
# ./build.sh install  — собрать и поставить на подключённый телефон (adb)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin"
ADB="$SDK/platform-tools/adb"
KS="$HOME/.android/debug.keystore"
# d8 и apksigner — скрипты, которые зовут java из PATH; системной Java нет, берём из Android Studio
export JAVA_HOME="${JBR%/bin}" PATH="$JBR:$PATH"

rm -rf build && mkdir -p build/gen build/classes build/dex

# Отладочный ключ подписи: одна и та же подпись нужна, чтобы обновлять приложение без удаления
if [[ ! -f "$KS" ]]; then
  "$JBR/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

"$BT/aapt2" compile --dir res -o build/res.zip
# --debug-mode: debuggable — нужен для sync.sh (adb run-as пишет в данные приложения)
# Версия растёт с каждой сборкой (минуты с 01.01.2026): Updater ставит только то, что новее установленного
VCODE=$(( ($(date +%s) - 1767225600) / 60 ))
VNAME="0.3-$(git rev-parse --short HEAD 2>/dev/null || echo nogit)$(git diff --quiet HEAD 2>/dev/null || echo '+')"
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml build/res.zip \
  --java build/gen --min-sdk-version 30 --target-sdk-version 36 --debug-mode \
  --version-code "$VCODE" --version-name "$VNAME" --replace-version

"$JBR/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM" -classpath "$BT/core-lambda-stubs.jar" -Xlint:-options -nowarn \
  -d build/classes $(find build/gen src -name '*.java')
"$BT/d8" --min-api 30 --output build/dex --lib "$PLATFORM" $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out build/Dictate.apk build/aligned.apk
echo "$VCODE $VNAME" > build/version.txt
echo "Built: build/Dictate.apk $VNAME ($VCODE, $(du -h build/Dictate.apk | cut -f1))"

if [[ "${1:-}" == "install" ]]; then
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "Телефон не подключён (adb devices пуст): кабель данных напрямую в Mac, разблокировать, разрешить отладку по USB" >&2; exit 1
  fi
  # После обновления Android не переподключает текущую клавиатуру — она «сворачивается» и не вызывается.
  # Если Dictate была выбрана — возвращаем Gboard (обычную клавиатуру пользователь), на Dictate он переключится сам
  CUR=$("$ADB" shell settings get secure default_input_method | tr -d '\r')
  "$ADB" install -r build/Dictate.apk
  if [[ "$CUR" == app.dictate/* ]]; then
    GBOARD=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
    if "$ADB" shell ime list -s | grep -q "$GBOARD"; then
      "$ADB" shell ime set "$GBOARD" >/dev/null
      echo "Клавиатура переключена на Gboard — Dictate выбрать заново кнопкой на панели навигации"
    else
      echo "⚠️ Gboard не включён — выбери клавиатуру на телефоне вручную (Dictate после обновления надо выбрать заново)"
    fi
  fi
fi
