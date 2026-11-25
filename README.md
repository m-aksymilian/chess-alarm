# Chess Alarm

A personal Android alarm clock that only stops when you solve a chess puzzle.

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
push; download it from the run's **Artifacts** (`chess-alarm-apk`).

## Signing (repository secrets)

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the release keystore (PKCS12), base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `chessalarm` |
| `KEY_PASSWORD` | key password (same as the keystore password) |
| `SPOTIFY_CLIENT_ID` | optional: bakes your Spotify client ID into the build (it can also be entered in Settings) |

Keep the keystore: Android only installs updates signed with the same key.

## Spotify

Create an app at developer.spotify.com, select **Android** and **Web API**, then register:

- Redirect URI: `chessalarm://spotify-callback`
- Android package: `app.chessalarm`
- SHA-1: the release certificate's SHA-1 (also shown in the app under Settings › Spotify)

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
