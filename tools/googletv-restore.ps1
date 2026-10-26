# Undo the Google TV debloat. Run: powershell -ExecutionPolicy Bypass -File googletv-restore.ps1 <tv-ip:port>
param([string]$s = '192.168.1.105:44545')
adb connect $s
adb -s $s shell pm enable com.google.android.apps.tv.launcherx
adb -s $s shell pm enable com.google.android.tungsten.setupwraith
foreach ($p in 'com.google.android.tvrecommendations','com.google.android.katniss','com.google.android.videos','com.google.android.feedback','com.android.tv.feedbackconsent','com.google.android.play.games','com.google.android.tv.remotecontrol.logging') {
  adb -s $s shell cmd package install-existing $p
}
