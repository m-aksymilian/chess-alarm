# Chess Alarm

An Android alarm clock that only stops when you solve a chess puzzle.

## Download

**[Download the latest APK](https://github.com/m-aksymilian/chess-alarm/releases/latest/download/chess-alarm.apk)**
(Android 8.0 or newer). All versions: [Releases](https://github.com/m-aksymilian/chess-alarm/releases).

1. Open the link on your phone and download `chess-alarm.apk`.
2. Tap the download. If asked, allow your browser or Files app to "install unknown apps", then tap **Install**.
3. Open Chess Alarm and tap **Allow** on each item on the welcome screen (notifications, alarms & reminders,
   full-screen alarm, unrestricted battery, display over other apps; Do Not Disturb access is optional).
4. Settings › **Test alarm in 10 seconds**, then lock your phone to check it rings.

New versions install over the old one and keep your alarms. Spotify is optional: to use it, create your own
app in the Spotify Developer Dashboard (see [Spotify](#spotify)) and paste its client ID in Settings.

- Native Kotlin + Jetpack Compose, min SDK 26, target SDK 37.
- 1000 short Lichess puzzles (CC0) bundled in `app/src/main/assets/puzzles.json`.
- The alarm plays from a foreground service, survives the power button, Doze and reboots, and can
  only be ended by finishing the puzzle (or the optional emergency code).

## Build

```
./gradlew :app:testDebugUnitTest      # chess rules (perft), puzzle logic, alarm state machine, time maths
./gradlew :app:assembleRelease        # signed if KEYSTORE_* env vars are set, else debug-signed
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # renders screens to app/build/screenshots
```

GitHub Actions (`.github/workflows/build.yml`) runs the tests and builds a signed release APK on every
push (as a run artifact). Pushes to the default branch are also published as a GitHub Release.

## Signing

The release keystore is committed in `app/signing/` (alias `chessalarm`), so CI needs no secrets.
Note: because this repository is public, anyone can sign an APK with this key that Android would accept
as an update to Chess Alarm. Only install builds from this repository's Releases page. To close that gap,
move signing to repository secrets (`KEYSTORE_PATH` from a decoded `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`) with a new key; existing installs would need one reinstall. Keep a backup of `app/signing/`: Android only installs updates signed with the same key.
Optional secret: `SPOTIFY_CLIENT_ID` bakes your Spotify client ID into the build (it can also be
entered in Settings).

## Spotify

Create an app at developer.spotify.com, select **Android** and **Web API**, then register:

- Redirect URI: `chessalarm://spotify-callback`
- Android package: `app.chessalarm`
- SHA-1: `CB:DE:8D:DC:EB:B0:A1:01:73:6F:84:30:34:8A:5B:81:2C:7E:48:AF` (also shown in the app under Settings › Spotify)

In development mode, add your Spotify account under **User Management**. Paste the client ID in
Settings › Spotify (or set the `SPOTIFY_CLIENT_ID` secret). Without Spotify, alarms use the built-in
fallback sound.

## Credits

- Puzzles: [Lichess puzzle database](https://database.lichess.org/#puzzles), CC0. Regenerate with
  `tools/select_puzzles.py`.
- Chess pieces: cburnett set by Colin M.L. Burnett (GPLv2+ / BSD / CC BY-SA 3.0), converted with
  `tools/svg_to_vector.py`.
- Alarm tones and sound effects: original, synthesised by `tools/make_sounds.py`.
- Spotify App Remote SDK (`app/libs`) and Auth SDK © Spotify AB, Apache 2.0.
