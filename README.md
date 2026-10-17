# Magic Music V

![platform](https://img.shields.io/badge/platform-Android_12%2B*-3DDC84.svg?logo=android)
![written on](https://img.shields.io/badge/written_on-Kotlin-7F52FF.svg?logo=kotlin&logoColor=white)
![release](https://img.shields.io/github/v/release/palmdm/MMV)
![downloads](https://img.shields.io/github/downloads/palmdm/MMV/total)
![last commit](https://img.shields.io/github/last-commit/palmdm/MMV)
![stars](https://img.shields.io/github/stars/palmdm/MMV.svg)
[![](https://img.shields.io/github/forks/palmdm/MMV.svg)](https://github.com/palmdm/MMV/network/members)
![issues open](https://img.shields.io/github/issues/palmdm/MMV)
![license](https://img.shields.io/badge/license-MIT-blue)
[![Donate](https://img.shields.io/badge/Donate-%E2%9D%A4-ff69b4?logo=ko-fi&logoColor=white)](https://z15.neocities.org/donate/pay)

**Magic Music V** is an Android app that uses the vibration motor to create a tactile experience that matches the music you're listening to. It's not a continuous buzz, but rather a series of discrete and gradually increasing knocks.

The app is designed for devices with the best vibration functions, but it also supports other brands. In the future, it may be available for devices with tactile response or similar features, not just Android smartphones.

As for the iPhone, I can't promise anything because I don't have one and don't plan to buy one. But if you can send it to me for free or test it yourself, I'll do the same, but only after buying the MacBook.

> [!NOTE]
> **Built for OnePlus and realme first.** Those phones (and their OPPO siblings) ship X-axis linear actuators — the hardware that can actually do what this app is for. Everything else is best-effort. First developed for OnePlus 15, There are plans to test on an old device - the Realme GT Neo 3.

*At the moment, it is not possible to test on a device with a version lower than Android 16, so I left the 12 version as the most suitable. I have plans to test the work on other devices.

---

## How it works

```
audio source ──► ring buffer ──► 1024-pt FFT ──► 3-band spectral flux
(playback capture,           (hop 256 / 5.3 ms)        │
 microphone, local                                     ├──► adaptive median threshold ──► onsets
 library file or Namida)                               └──► onset envelope ──► autocorrelation ──► BPM + beat phase
                                                                                        │
                                               onsets + predicted beats ──► HapticEngine ──► composition primitives
```

Four things can feed the analyser now: live **System audio** capture, the **Microphone**,
a track picked straight from your own **Local library**, or whatever is currently playing in
**Namida**. The last two don't need to analyse in real time at all — see below.

### Two backends, plus an experimental third

| Backend | Where it runs | What it is |
|---|---|---|
| AOSP | Any device that supports `arePrimitivesSupported()` | The stock `VibrationEffect.Composition` primitives, including `CLICK`, `TICK`, `LOW_TICK`, and `THUD`. |
| O-Haptics | OnePlus, realme, OPPO | A reflection bridge into `com.oplus.os.LinearmotorVibrator`/`WaveformEffect`, the vendor's own tuned effect library. |
| O-Haptics by MMV *(experimental)* | OnePlus, realme, OPPO | **Purely additive** — adds a second, quieter "garnish" effect about 28 milliseconds after a hard hit or accent on top of the normal O-Haptics tap. |

Backend selection is **detect-first, manual-override-second**: at launch the app probes the
actual hardware capability (`Vibrator.arePrimitivesSupported()` and reflection checks for the
OPLUS motor classes) rather than trusting `Build.MANUFACTURER`/`Build.MODEL` strings, because
those can be wrong — a root-level model spoof on real
OnePlus/realme/OPPO hardware. Auto picks AOSP whenever primitives are supported, otherwise
O-Haptics if it resolves, otherwise falls back to AOSP. "O-Haptics by MMV" is reachable only
by explicitly choosing it in Setup — it is marked experimental and is never chosen by Auto.

### Why primitives, not waveforms

`createOneShot`/`createWaveform` drive the actuator for a duration you pick. On an LRA it
keeps ringing after the pulse ends, and you feel a smear. A composition primitive
(`PRIMITIVE_CLICK`, `PRIMITIVE_TICK`, `PRIMITIVE_LOW_TICK`, `PRIMITIVE_THUD`) is a
factory-tuned impulse with braking, which is what makes it read as a *tap*. `minSdk` is
31 because that is where the capability-probe APIs live.

### Why three bands

The taps have to differ or the whole thing is one dumb metronome.

| band | range | primitive | gain |
|---|---|---|---|
| low | 30–190 Hz | `THUD` | 1.00 |
| mid | 190–1000 Hz | `CLICK` | 0.80 |
| high | 2–9 kHz | `TICK` / `LOW_TICK` under 0.5 | 0.55 |

Scale within a band comes from onset strength curved by `strength^0.6` — perceived haptic
magnitude is compressive, so a linear map wastes the top half of the range. Below 0.12 the
actuator doesn't break static friction, so those taps are clamped up rather than fired
uselessly.

### Why a beat predictor exists at all

A reactive tap can never be early: it carries the audio buffer plus one analysis hop, and
it disappears whenever the beat is implied rather than struck. The autocorrelation tracker
gives tempo and phase, so beats can be **scheduled** — the only way to place a tap exactly
on, or deliberately ahead of, the musical event. That's what the negative side of the
timing offset slider is for.

The tracker biases towards 100–140 BPM. Without a prior, autocorrelation flips between a
tempo and its half or double mid-track and the taps audibly halve. During silence the
envelope mean collapses towards zero, so confidence is forced to 0 and the beat lock resets
instead of reporting a false-confident grid and firing endless taps into nothing.

### Why delays go into the composition

Batched taps go to the vibrator service as one composition with per-primitive delays,
instead of being posted on a `Handler`. App-side timers inherit whole-millisecond
scheduler jitter, enough to smear a hi-hat pattern. Composition delays are applied in the
HAL and hold their spacing while the UI thread is busy. O-Haptics effects add their own
small, vendor-controlled jitter on top of that — not something this app can remove — which
is part of why "O-Haptics by MMV" stays additive rather than replacing the base tap.

### Modes

- **Onsets** — every transient taps. Most detail, always a few ms late.
- **Beat** — only the predicted grid. Clean, ignores fills, can run ahead of the audio.
- **Hybrid** — grid carries the pulse, mid/high onsets ride on top at 70 % scale, and
  low-band onsets landing on a grid beat are suppressed so you don't get a double-tap.

---

## Local library and Namida

Besides the two live-capture sources, the app can work entirely offline against your own
files, or quietly follow another player:

- **Local library** — pick a folder once through the system file picker, Magic Music V indexes
  every track in it and analyses each one **offline**, in the background, using 2–4
  parallel workers so a few hundred tracks finish in a reasonable time instead of one at a
  time. Analysed tracks are cached on-device (no re-analysis on replay) and get their own
  built-in player with a tap timeline already baked in. In the Library tab you can:
  - search by any substring, not just from the start of the filename;
  - tap anywhere on a track row to select it, or use its checkbox to multi-select for a
    batch "Analyse selected" pass instead of re-scanning the whole folder;
  - drop a matching `.lrc` lyrics file next to a track (same filename, same folder) to get
    exact vocal-timing data — see **Lyrics-gated notes** below.
- **Namida** — if [Namida](https://github.com/MSOB7YY/Namida) is installed, Magic Music V
  connects to its media session (no root, no Xposed) the same way any lock-screen media
  widget would, reads what's currently playing and its live position, and matches it
  against the local library cache by filename to drive the same offline tap timeline in
  sync with Namida's own playback — including re-anchoring cleanly when you seek.

> [!TIP]
> If you're picking a player for this, [Namida](https://github.com/MSOB7YY/Namida) with its
> flac equalizer is worth it on its own merits, not just for the integration. Just a piece
> of candy!

## OnePlus / realme notes

**System vibration intensity scales on top of yours.** Settings → Sounds & vibration →
Vibration intensity multiplies every app's output. If the taps feel weak at 100 %
in-app, that slider is usually why. There's an **Ignore system intensity** switch in
Setup if you'd rather the app's own intensity slider be the only thing in control.

**Motor tiers.** The app probes `arePrimitivesSupported()` at launch and tells you which
tier you're on:

| tier | hardware | behaviour |
|---|---|---|
| FULL | X-axis LRA — OnePlus flagships & Nord high end, realme GT/Pro | I'm trying to implement it, it's not about other devices |
| PARTIAL | Z-axis LRA, no `THUD` | kicks fall back to `CLICK`, thinner |
| NONE | rotary ERM — realme C/Narzo, some Nord N | physically cannot tap; you get a buzz |
| VENDOR_ONLY | OPLUS motor classes present but AOSP primitives unsupported | O-Haptics backend only, AOSP is skipped |

The NONE case is stated plainly instead of quietly buzzing. A rotor spins up and coasts
down over tens of milliseconds; no software fixes that.

### Effect lab

Setup also has a small per-band "Test" control for each haptic preset — fire a single
sample tap for the low/mid/high band on demand, without needing music playing, so you can
feel what a preset actually does before picking it.

### Slider detents

The Intensity, Sensitivity and Timing-offset sliders in Tune give a light, discrete haptic
"knock" every time you cross a division, so you can feel your way to a setting without
watching the screen. It tries an OPLUS keyboard-tick effect first on O-Haptics devices,
then falls back to an AOSP `TICK`/`LOW_TICK` composition primitive, then to the plain
predefined `EFFECT_TICK` — so it works, at some fidelity, on non-OnePlus/realme devices too.

---

## Audio sources, and the one thing that will annoy you

**System audio** uses `AudioPlaybackCaptureConfiguration` behind the screen-capture
prompt. Exact and immune to room noise, **but Android only lets it see apps whose capture
policy allows it.** Local players are generally
capturable. Spotify and YouTube Music set `ALLOW_CAPTURE_BY_NONE` and produce silence. No
permission fixes this — the block is on the playing app's side. At startup, it is
recommended that you choose not to broadcast the entire screen, but only the application in
which the content is being played. This does not exclude accidental events at this stage of
development, but it significantly increases the efficiency of the application.

**Microphone** works with anything audible on speakers, never on headphones. It opens
`AudioSource.UNPROCESSED` to bypass AGC and noise suppression, both of which flatten
transients — the only thing this app measures.

**Local library** and **Namida** sidestep all of this — no capture policy, no room noise,
because the tap timeline comes from offline analysis of the file itself, not from listening
to it play.

## Diagnostics

A capability report (which primitives resolved, which OPLUS effects are available vs.
known-bad, last error if any) lives behind the triple-tap on the version number in About —
see above. It's meant for filing an issue, not for everyday use.

---

## Build

The application is built automatically on every push via GitHub Actions. Each run produces
per-architecture APKs (`armeabi-v7a`, `arm64-v8a`) and a universal APK, signed with a
release keystore when one is configured in repo secrets, debug-signed otherwise.

Local APK Build:

```bash
gradle wrapper --gradle-version 8.11.1   # once, generates ./gradlew
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Recommended ools for build: JDK 17, Android SDK 36.

---

## Tuning notes

> [!NOTE]
> The current settings that the application sets immediately are the most optimal from the entry-level point of view.

- **Sensitivity** scales the adaptive threshold. ~1.3 for sparse electronic, 1.8–2.2 for
  dense mixes. Too low and taps run together into vibration, which defeats the point.
- **Timing offset** is the one to dial in by feel. Audio-haptic simultaneity has roughly a
  ±50 ms window; inside it, taps read as *part of* the music rather than a response to it.
- Rate limiting is per band (80 / 55 / 40 ms minimum gap). Raise it first if the motor
  starts heating.
- At startup, it is recommended that you choose not to broadcast the entire screen, but only the application in which the content is being played. This does not exclude accidental events at this stage of development, but it significantly increases the efficiency of the application.

## Known limits

- Battery cost is real. Continuous LRA driving isn't free.
- Tempo tracking assumes a roughly steady pulse. Rubato, free time and heavy swing keep
  confidence below the lock threshold; use Onsets mode there.
- `Vibrator.getCompositionSizeMax()` isn't public API, so batches are capped at 8
  primitives and `compose()` is wrapped in try/catch.
- Lyrics-gated notes only work where exact playback position is known (Local library,
  Namida) and only when a matching `.lrc` file is present next to the track; System audio
  and Microphone capture fall back to accent-based notes.
- "O-Haptics by MMV" is experimental: it's additive by design, never auto-selected, and
  not guaranteed to resolve a working effect on every OPLUS device.
