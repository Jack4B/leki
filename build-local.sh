#!/usr/bin/env bash
# Lokalne budowanie APK (wersja debug). Wymaga: Node 20+, Java 21, Android SDK (ANDROID_HOME).
set -euo pipefail
cd "$(dirname "$0")"
npm ci
npm run check
if [ -d android ]; then
  node scripts/patch-android.js
  npx cap sync android
else
  npm run android:init
fi
(cd android && ./gradlew assembleDebug)
echo "Gotowe: android/app/build/outputs/apk/debug/app-debug.apk"
