#!/usr/bin/env sh
set -e
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle nu este in PATH. Deschide proiectul in Android Studio si foloseste Build > Build APK(s)."
  exit 1
fi
gradle assembleDebug
echo "APK creat: app/build/outputs/apk/debug/app-debug.apk"
