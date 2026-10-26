#!/usr/bin/env bash
# Build the friend-safe share folder + zip: APK, installer, examples. Never includes ha.json (it holds a token).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf share glass-home-share.zip && mkdir share
cp glass-home.apk install-glass-home.bat uninstall-glass-home.bat ha.example.json buttons.example.json favorites.example.json share/
python -c "import shutil; shutil.make_archive('glass-home-share', 'zip', 'share')"
echo "share/ and glass-home-share.zip ready"
