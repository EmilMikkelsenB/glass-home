@echo off
chcp 65001 >nul
setlocal
rem One-time setup of Glass Home on a Chromecast with Google TV / Google TV Streamer.
rem Needs: this folder (glass-home.apk next to this file) and Android platform-tools (adb).
rem Run it again any time to update the app; your settings stay.

cd /d "%~dp0"
where adb >nul 2>nul || (
  echo Installing adb ^(Android platform-tools^)...
  winget install --id Google.PlatformTools -e --accept-source-agreements --accept-package-agreements
  echo.
  echo adb was installed. Close this window and double-click install-glass-home.bat again.
  pause & exit /b
)

echo.
echo ===== Step 1: on the TV =====
echo  Settings ^> System ^> Developer options ^> Wireless debugging: ON
echo  (No Developer options? Settings ^> System ^> About ^> click "Android TV OS build" 7 times.)
echo  Then choose "Pair device with pairing code" and leave that screen open.
echo.
set /p PAIR=Type the IP address ^& port shown under the code (example 192.168.1.105:46239):
set /p CODE=Type the 6-digit code:
adb pair %PAIR% %CODE% || (echo Pairing failed. Get a new code on the TV and try again. & pause & exit /b)
echo.
echo ===== Step 2 =====
echo  Close the pairing popup. The Wireless debugging screen shows "IP address ^& Port".
set /p TV=Type that IP address ^& port (example 192.168.1.105:44545):
adb connect %TV%
adb -s %TV% get-state >nul 2>nul || (echo Could not connect. Check the address and try again. & pause & exit /b)

echo.
echo ===== Installing =====
adb -s %TV% install -r --no-incremental glass-home.apk || (echo Install failed. & pause & exit /b)
rem Google's home screen (and its setup helper that would switch it back on) off; Glass Home becomes Home
adb -s %TV% shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb -s %TV% shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb -s %TV% shell cmd package set-home-activity se.glass.home/.MainActivity
rem remote-button remapping (buttons.json); replaces other accessibility services such as Projectivy's
adb -s %TV% shell settings put secure enabled_accessibility_services se.glass.home/se.glass.home.KeyService
adb -s %TV% shell settings put secure accessibility_enabled 1
rem optional config files placed next to this script are copied to the TV
for %%F in (ha.json buttons.json favorites.json) do if exist "%%F" adb -s %TV% push "%%F" /sdcard/Android/data/se.glass.home/files/
adb -s %TV% shell input keyevent KEYCODE_HOME

echo.
echo Done! Press Home on the remote. You can turn Wireless debugging off again on the TV.
pause
