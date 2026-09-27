# Spotlet

A small, headless Spotify Connect receiver for Android. It's meant for old and slow devices
that sit on a shelf all day, like a rooted Echo Show 5.

Once installed, the device shows up as a speaker in the Spotify app on any phone or computer on
the same network. Audio is streamed and decoded on the device by
[librespot](https://github.com/librespot-org/librespot). Spotlet has no now-playing screen of
its own. It publishes playback to Android's media session instead, so the notification shade,
the lock screen, Bluetooth and headset buttons, and kiosk or dashboard apps can all show and
control what's playing.

## Features

- Shows up as a Spotify Connect speaker automatically. No login on the device.
- Media controls through Android's media session and a media notification, with title, artist,
  album, cover art, play/pause, next/previous and seeking.
- Handles audio focus. Music is turned down while a voice assistant or announcement speaks,
  pauses and resumes around short interruptions, and pauses when another app starts playing.
  This can be switched off.
- Keeps playing when the audio output changes, for example when a Bluetooth speaker connects or
  drops mid-track.
- Starts on boot, can be renamed without dropping the connection, and streams at 96, 160 or
  320 kbps (320 by default).
- A single settings screen built from plain Android widgets. The Kotlin part of the app is
  about 100 KB.

Needs Android 8.0 (API 26) or newer on a 32-bit or 64-bit ARM device.

## Install

Download `spotlet-<version>.apk` from a GitHub release, or the `spotlet-apk` artifact from a CI
run, and install it with adb:

```bash
adb install -r spotlet-0.1.0.apk
```

Open the app once. After that the receiver stays enabled and also starts after a reboot.

## Building

CI (`.github/workflows/build.yml`) builds the whole thing. To build locally:

1. The native core is only needed if you change `rust/` or want an APK that actually plays
   music. You need rustup, the Android NDK (r27 or newer) and `cargo install cargo-ndk`:
   ```bash
   rustup target add armv7-linux-androideabi aarch64-linux-android
   cd rust
   cargo ndk -t armeabi-v7a -t arm64-v8a --platform 26 -o ../app/src/main/jniLibs build --release
   ```
   Then copy the NDK's `libc++_shared.so` for each ABI into `app/src/main/jniLibs/<abi>/`, the
   same way the CI workflow does. You can also just unzip the `lib/` folder from a CI-built APK
   into `jniLibs/`.
2. Build the APK with `./gradlew assembleDebug`. JDK 17 or newer is required.

The `.so` files are not checked in. The JNI function names depend on the package name
`fi.qvr.spotlet.NativeBridge`, so renaming it means rebuilding the native core.

## Credits

Spotlet is based on [Rusty](https://github.com/SerafiniJose/rusty) by SerafiniJose, which is
MIT licensed. It was forked at `3b21763` (v2.7.0). The Rust core is Rusty's work, including its
librespot patches for CDN fallback, the discovery handshake panic and logins from desktop
clients. Spotlet keeps only the receiver part of it. Rusty's receiver was itself inspired by
[librespot-android-connect](https://github.com/willturr/librespot-android-connect).

Spotlet is not affiliated with Spotify and needs a Spotify Premium account.

## License

MIT, see [LICENSE](LICENSE).
