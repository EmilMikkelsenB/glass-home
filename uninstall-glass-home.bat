@echo off
setlocal
rem Puts the normal Google TV home screen back and removes Glass Home. Wireless debugging must be on (see install-glass-home.bat).
set /p TV=TV IP address ^& port from the Wireless debugging screen (example 192.168.1.105:44545):
adb connect %TV%
adb -s %TV% shell pm enable com.google.android.apps.tv.launcherx
adb -s %TV% shell pm enable com.google.android.tungsten.setupwraith
adb -s %TV% shell settings delete secure enabled_accessibility_services
adb -s %TV% uninstall se.glass.home
adb -s %TV% shell input keyevent KEYCODE_HOME
echo Done. The Google TV home screen is back.
pause
