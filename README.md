# Glass Home

A home screen (launcher) for Chromecast with Google TV / Google TV Streamer: iOS 26 "Liquid Glass" style, big centred clock,
real app logos on brand-coloured glass, an app folder with a blurred background, Home Assistant scenes/lights/sensors, and
remote-button remapping. No ads, no recommendations. Plain Java, built without Gradle.

Private project. The wallpaper (macOS Sierra) and logos belong to Apple and the app makers. Don't publish builds.

![Home screen](docs/screenshots/home.jpg)

| Hemmet (Home Assistant) | Fler appar (all other apps) |
|---|---|
| ![Hemmet](docs/screenshots/hemmet.jpg) | ![Fler appar](docs/screenshots/fler-appar.jpg) |

Screenshots taken on the TV (1920×1080). The UI follows the TV's language (Swedish here).

## Layout

| Path | What |
|---|---|
| `src/se/glass/home/` | `MainActivity` (home screen, folder, Home Assistant panel), `HomeAssistant` (REST client), `KeyService` (button remapping, accessibility service), `Glass` (glass drawing) |
| `res-src/` | hand-written resources (accessibility config, strings) |
| `artwork/make_assets.py` | generates glass icons, banners, wide buttons and wallpapers into `artwork/out/` (`APPS` = name, fallback glyph, brand colour) |
| `artwork/fetch_logos.py` | downloads wallpapers + Simple Icons logos, and pulls the rest from app APKs, into `artwork/logos/` |
| `build.sh` | builds `glass-home.apk` (aapt2 → javac → d8 → zipalign → apksigner) |
| `make-share.sh` | friend-safe `share/` + zip (APK, installer, examples, no token) |
| `install-glass-home.bat` / `uninstall-glass-home.bat` | double-click installer / undo for Windows |
| `tools/googletv-restore.ps1` | re-enables the Google apps removed during the debloat |
| `*.example.json` | templates for the TV config files |

## Build

Needs JDK 17, Android SDK build-tools 35.0.0 + platform android-35 (default `%USERPROFILE%\Android\sdk`, override with `GLASS_SDK`),
Python 3 with Pillow, numpy, svgelements. Run in Git Bash:

```bash
python artwork/fetch_logos.py <dir with app APKs pulled from the TV>   # once; without APKs only Simple Icons logos are fetched
python artwork/make_assets.py
bash build.sh                       # → glass-home.apk (creates debug.keystore on first run)
bash build.sh --install <tv-ip:port>   # also installs, sets it as Home, restarts it
bash make-share.sh                  # → share/ + glass-home-share.zip
```

## Config on the TV

`/sdcard/Android/data/se.glass.home/files/`: `ha.json` (HA URL, token, tiles, chips, names), `buttons.json` (remote buttons),
`favorites.json` (front-row apps). See the `*.example.json` files. The installer copies any of these that sit next to it.
