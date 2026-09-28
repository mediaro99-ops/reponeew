@echo off
where gradle >nul 2>nul
if %errorlevel% neq 0 (
  echo Gradle nu este in PATH. Deschide proiectul in Android Studio si foloseste Build ^> Build APK(s).
  pause
  exit /b 1
)
gradle assembleDebug
if %errorlevel% neq 0 exit /b %errorlevel%
echo.
echo APK creat: app\build\outputs\apk\debug\app-debug.apk
pause
