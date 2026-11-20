# MemoOnly

MemoOnly is the MemoMind companion Android app that puts the phone's workout and
music onto the smart glasses HUD over Bluetooth LE. It replaces the separate
Cardio and Music apps with one APK and two tabs.

- **Cardio tab** — reads live heart rate from any standard Bluetooth LE Heart
  Rate source (chest strap, wearable), adds GPS distance / speed / time, and
  streams the metrics to the glasses cardio plugin (channel `0x4344`).
- **Music tab** — reads the phone's now-playing MediaSession (title, artist,
  cover, position) and streams it to the glasses music plugin (channels
  `0x4D55` TRACK, `0x4D54` COVER, `0x4D56` EVENT for the play/pause button).

Each tab starts its own foreground service, so both keep working with the
screen off. A single glasses connection carries both plugin protocols.

## Glasses plugins

- Cardio: `GlassSDK/examples/cardio/` (`cardio.gmp`)
- Music: `GlassSDK/examples/music/` (`music.gmp`)

The app works with either plugin running; the cardio display needs
`cardio.gmp`, the music display needs `music.gmp`.

## Build

```
./gradlew assembleRelease
```

Signed with `app/release-key.jks` (alias `cardio-alias`, shared with the former
Cardio app so upgrades keep working).

## Status

Built and signed locally; not yet validated on physical glasses.
