@echo off
rem Install Detach on an Android phone over USB.
rem Usage: install-usb.bat Detach-<version>.apk
if "%~1"=="" (
  echo Usage: %~nx0 path\to\Detach-version.apk
  exit /b 1
)
if not exist "%~1" (
  echo File not found: %~1
  exit /b 1
)
where adb >nul 2>nul
if errorlevel 1 (
  if exist "%~dp0adb.exe" (set "ADB=%~dp0adb.exe") else (
    echo adb not found. Download Android SDK Platform-Tools and put adb.exe next to this script.
    exit /b 1
  )
) else (set "ADB=adb")

echo Waiting for the phone... plug it in and tap "Allow" on the USB debugging popup.
"%ADB%" wait-for-device
echo Installing %~1 ...
"%ADB%" install -r "%~1"
if errorlevel 1 exit /b 1
rem Best effort: lets Detach turn itself back on after a "pause for payments" (see docs\INSTALL.md).
"%ADB%" shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS >nul 2>nul
if errorlevel 1 (
  echo Note: could not grant WRITE_SECURE_SETTINGS. After a pause you will turn Detach back on by hand.
) else (
  echo Allowed Detach to turn itself back on after a pause.
)
"%ADB%" shell am start -n io.github.detach/.MainActivity >nul 2>nul
echo.
echo Done. On the phone: Accessibility - Detach - turn on "Use Detach".
echo You can turn USB debugging off afterwards; Detach keeps working.
