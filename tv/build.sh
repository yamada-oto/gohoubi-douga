#!/usr/bin/env bash
# テレビ版アプリ(APK)を作る。Gradle は使わず、Android SDK の道具だけで組み立てる。
# 使い方: bash tv/build.sh [版の番号]    (ANDROID_HOME に Android SDK があること。GitHub Actions にはある)
set -euo pipefail
cd "$(dirname "$0")"
N="${1:-1}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "ANDROID_HOME がありません"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PF="$(ls -d "$SDK"/platforms/android-* | grep -E 'android-[0-9]+$' | sort -V | tail -1)/android.jar"
echo "build-tools: $BT"; echo "platform: $PF"; java -version 2>&1 | head -1

rm -rf build; mkdir -p build/gen build/classes build/dex build/src
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$PF" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$N" --version-name "0.1.$N" build/res.zip

cp -r src/. build/src/
sed -i "s/__VERSION__/0.1.$N/" build/src/io/github/yamadaoto/gohoubi/MainActivity.java
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PF" -Xlint:-options -d build/classes \
  $(find build/src build/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$PF" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# 署名の鍵: リポジトリには置かない。GitHub の Secrets(TV_KEYSTORE_B64・TV_KEYSTORE_PASS)にあればそれを使う。
# なければ その場かぎりの鍵で署名する(その場合、入れなおすときは先にテレビから消す必要がある)
KS=build/sign.keystore
if [ -n "${TV_KEYSTORE_B64:-}" ] && [ -n "${TV_KEYSTORE_PASS:-}" ]; then
  echo "$TV_KEYSTORE_B64" | tr -d ' \n\r' | base64 -d > "$KS"; KSPASS="$TV_KEYSTORE_PASS"; echo "sign: fixed key"; echo "固定の鍵" > build/signmode
else
  KSPASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$KSPASS" -keypass "$KSPASS" -alias gohoubi \
    -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=gohoubi-douga tv" >/dev/null 2>&1
  echo "sign: one-time key"; echo "その場かぎりの鍵" > build/signmode
fi
KSPASS="$KSPASS" "$BT/apksigner" sign --ks "$KS" --ks-pass env:KSPASS --ks-key-alias gohoubi \
  --out build/gohoubi-tv.apk build/aligned.apk
rm -f "$KS"
"$BT/apksigner" verify build/gohoubi-tv.apk
ls -l build/gohoubi-tv.apk
# 2026-11-12: 固定の鍵で作りなおす
