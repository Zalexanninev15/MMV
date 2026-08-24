# Magic Music V

![platform](https://img.shields.io/badge/platform-Android_15.0%2B*-3DDC84.svg?logo=android)
![version](https://img.shields.io/badge/version-0.2.0_alpha-orange)
![license](https://img.shields.io/badge/license-MIT-blue)

Android app that **taps** the vibration motor in time with whatever music is playing —
discrete, graded knocks, not a continuous buzz. **Magic Music V** for creating pleasant tactile feedback when playing music and videos (and in the future). It focuses on devices with the best vibration functions. Other devices from different brands are accepted. Perhaps in the future, there will be support not only for Android smartphones, but also for other devices with tactile response or something similar.

A note regarding the Android version for work:
*At the moment, it is not possible to test on a device with a version lower than Android 16, so I left the 15th version as the most suitable. I have plans to test the work on other devices.

> [!NOTE]
> **Built for OnePlus and realme first.** Those phones (and their OPPO siblings) ship X-axis linear actuators with the full `VibrationEffect.Composition` primitive set, which is the only hardware that can actually do what this app is for. Everything else is best-effort. First developed for OnePlus 15, There are plans to test on an old device - the Realme GT Neo 3.

---

## How it works

```
audio source ──► ring buffer ──► 1024-pt FFT ──► 3-band spectral flux
(playback capture            (hop 256 / 5.3 ms)        │
 or microphone)                                        ├──► adaptive median threshold ──► onsets
                                                       └──► onset envelope ──► autocorrelation ──► BPM + beat phase
                                                                                       │
                                              onsets + predicted beats ──► HapticEngine ──► composition primitives
```

### Why primitives, not waveforms

`createOneShot`/`createWaveform` drive the actuator for a duration you pick. On an LRA it
keeps ringing after the pulse ends, and you feel a smear. A composition primitive
(`PRIMITIVE_CLICK`, `PRIMITIVE_TICK`, `PRIMITIVE_LOW_TICK`, `PRIMITIVE_THUD`) is a
factory-tuned impulse with braking, which is what makes it read as a *tap*. `minSdk` is
31 because that is where the capability-probe APIs live; below it the premise degrades
silently into a buzz.

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
tempo and its half or double mid-track and the taps audibly halve.

### Why delays go into the composition

Batched taps go to the vibrator service as one composition with per-primitive delays,
instead of being posted on a `Handler`. App-side timers inherit whole-millisecond
scheduler jitter, enough to smear a hi-hat pattern; composition delays are applied in the
HAL and hold their spacing while the UI thread is busy.

### Modes

- **Onsets** — every transient taps. Most detail, always a few ms late.
- **Beat** — only the predicted grid. Clean, ignores fills, can run ahead of the audio.
- **Hybrid** — grid carries the pulse, mid/high onsets ride on top at 70 % scale, and
  low-band onsets landing on a grid beat are suppressed so you don't get a double-tap.

---

## OnePlus / realme notes

These three are the same OPLUS platform underneath, and they need handling AOSP doesn't.

**The system will freeze the app.** A foreground service is not a promise on ColorOS,
OxygenOS or realme UI. The battery layer suspends apps that aren't whitelisted once the
screen goes off — no ANR, no log line, no callback, the audio thread just stops being
scheduled and the taps stop. There's no API to detect it, so the app shows a card with
two buttons:

- **Unrestrict battery** → the standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog.
- **Auto-start** → the ColorOS auto-start list. That component has moved with nearly every
  ColorOS release and has no stable action, so `OemSupport` walks a list of known
  `ComponentName`s newest-first and falls back to the app info page.

Both are needed. One alone is not enough.

**System vibration intensity scales on top of yours.** Settings → Sounds & vibration →
Vibration intensity multiplies every app's output. If the taps feel weak at 100 %
in-app, that slider is usually why.

**Motor tiers.** The app probes `arePrimitivesSupported()` at launch and tells you which
tier you're on:

| tier | hardware | behaviour |
|---|---|---|
| FULL | X-axis LRA — OnePlus flagships & Nord high end, realme GT/Pro | what the app is designed for |
| PARTIAL | Z-axis LRA, no `THUD` | kicks fall back to `CLICK`, thinner |
| NONE | rotary ERM — realme C/Narzo, some Nord N | physically cannot tap; you get a buzz |

The NONE case is stated plainly instead of quietly buzzing. A rotor spins up and coasts
down over tens of milliseconds; no software fixes that.

---

## Audio sources, and the one thing that will annoy you

**System audio** uses `AudioPlaybackCaptureConfiguration` behind the screen-capture
prompt. Exact and immune to room noise, **but Android only lets it see apps whose capture
policy allows it.** Local players (Poweramp, AIMP, VLC, Musicolet, Vinyl) are generally
capturable. Spotify and YouTube Music set `ALLOW_CAPTURE_BY_NONE` and produce silence. No
permission fixes this — the block is on the playing app's side.

**Microphone** works with anything audible on speakers, never on headphones. It opens
`AudioSource.UNPROCESSED` to bypass AGC and noise suppression, both of which flatten
transients — the only thing this app measures.

---

## Build

The application is built automatically when you commit to the repository, you can download it from Actions, then the name of the build (look for a successful one, with a check mark) and there will be a ZIP archive with the APK installer at the bottom.

The APK is **debug-signed** on purpose — a personal-use app doesn't need a keystore in
repo secrets. For signed releases, add a `signingConfigs` block to `app/build.gradle.kts`
and feed it a base64 keystore from secrets.

Local APK Build:

```bash
gradle wrapper --gradle-version 8.11.1   # once, generates ./gradlew
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tools: JDK 17, Android SDK 36. `gradle-wrapper.jar` isn't committed because it's a binary.

---

## Tuning notes

> [!NOTE]
> The current settings that the application sets immediately are the most optimal from the entry-level point of view. However, I recommend that you adjust the audio to your perception of the sound.

- **Sensitivity** scales the adaptive threshold. ~1.3 for sparse electronic, 1.8–2.2 for
  dense mixes. Too low and taps run together into vibration, which defeats the point.
- **Timing offset** is the one to dial in by feel. Audio-haptic simultaneity has roughly a
  ±50 ms window; inside it, taps read as *part of* the music rather than a response to it.
- Rate limiting is per band (80 / 55 / 40 ms minimum gap). Raise it first if the motor
  starts heating.
- At startup, it is recommended that you choose not to broadcast the entire screen, but only the application in which the content is being played. This does not exclude accidental events at this stage of development, but it significantly increases the efficiency of the application.

## Known limits

- Battery cost is real. Continuous LRA driving isn't free.
- The low band has only ~4 usable FFT bins. A time-domain lowpass envelope follower would
  track kicks better and is the obvious next improvement.
- Tempo tracking assumes a roughly steady pulse. Rubato, free time and heavy swing keep
  confidence below the lock threshold; use Onsets mode there.
- `Vibrator.getCompositionSizeMax()` isn't public API, so batches are capped at 8
  primitives and `compose()` is wrapped in try/catch.
