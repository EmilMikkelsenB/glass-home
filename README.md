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
| `src/se/glass/home/` | `MainActivity` (home screen, folder, panels, Jellyfin row), `HomeAssistant` / `Jellyfin` (REST clients), `Updater` + `InstallReceiver` (self-update from releases), `KeyService` (button remapping, accessibility service), `Glass` / `Poster` (drawing) |
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

`/sdcard/Android/data/se.glass.home/files/`, see the `*.example.json` files. The installer copies any of these that sit next to it:

| File | Enables |
|---|---|
| `ha.json` | Hemmet panel: HA URL, token, tiles, chips, display names |
| `jellyfin.json` | "Fortsätt titta" row (resume + next up), opens items directly in the Jellyfin app |
| `update.json` | self-update: the home screen shows "Uppdatering finns" when a newer GitHub release exists (token: fine-grained, this repo, Contents read-only) |
| `buttons.json` | remote-button remapping |
| `favorites.json` | front-row apps |

## Releases and updates

Every push to `main` builds and publishes a release (`.github/workflows/release.yml`). It needs the repo secret `KEYSTORE_B64`
(base64 of `debug.keystore`) so releases are signed with the same key and install over older builds. Each release's notes
carry `versionCode=N`, and the TV compares that against its installed version.
