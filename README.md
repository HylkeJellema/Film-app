# KickerCam

An Android app for filming yourself doing sport on a **fixed camera** — a tripod pointed at a
wakeboard kicker, a jump, a rail, a landing. The camera rolls continuously, an on-device AI watches a
box you draw on the screen, and when you come through it the app writes out a clip covering the
seconds **before and after** the moment. You then flick through the results as small looping
previews of each trigger and export the ones you want.

Built for a Galaxy S26 Ultra (and any modern Android flagship): full lens selection including the 5x
tele, plus resolution, frame rate and optional manual shutter/ISO control.

---

## How it works

The important trick is that **the camera never stops recording**.

```
camera ──┬─► preview  (what you see)
         ├─► H.264/HEVC encoder ─► rolling in-memory ring buffer (last N seconds)
         └─► small YUV stream ───► detector (motion / object / pose)
                                        │
                                        └─ hit ─► mux [moment−5s … moment+5s] to an .mp4
```

Detection does not start the recording — it only decides which few seconds of an already-running
encode get written to disk. That is the only way the five seconds *before* the trick can exist at
all. A detector that starts recording on a trigger can never give you the run-up.

Everything runs offline. The ML models are bundled in the APK; the app requests no network
permission at all.

### Getting the moment right

The detector needs tens of milliseconds to reach a verdict, so the frame that fired is already in the
past by the time the trigger runs. Each verdict therefore carries **the sensor timestamp of the frame
that caused it**, and the clip is centred on that — not on the instant detection finished. The clip
lands where the action was.

Key frames are written once per second, so a saved clip starts at the key frame *at or before* the
requested point. You may get up to a second more pre-roll than asked for, never less.

---

## Detection modes

Pick per session in Settings. All four run entirely on the phone.

| Mode | What it does | Good for |
|---|---|---|
| **Motion** (default) | Luma frame differencing on a 64×36 grid. Measures activity inside the box *and* outside it, and only fires when inside clearly beats outside. | The reliable default. Works at distance where a rider is small in frame. |
| **Object tracking (AI)** | ML Kit on-device object detector in streaming mode. Fires when a tracked object overlaps the box and is actually moving. | Busy or moving backgrounds. |
| **Person / pose (AI)** | ML Kit on-device pose detector. Only fires when a human body overlaps the box. | Strictest trigger — but needs the rider reasonably large in frame. |
| **Motion + person confirm** | Motion opens the gate, pose confirms it was a person. | Fewest false positives. Slightly slower to fire. |

**Why motion is the default and not the AI.** Background subtraction is what actually solves this
problem outdoors. Waves, wind shaking the tripod, moving water and auto-exposure steps all change the
*whole frame*, so comparing inside-the-box against outside-the-box rejects them. A whole-frame jump is
ignored outright. Pose detection is a stronger signal when it fires, but a wakeboarder 30 m away
through a 5x lens is often too small for pose landmarks to lock on. Start on Motion; switch to
Motion + person confirm if something in your spot keeps triggering it.

Three further knobs guard against false positives: a run of **consecutive hit frames** is required, a
**cooldown** stops one pass making a dozen clips, and an **arming delay** gives you time to get out of
frame. If you take two hits in quick succession, the second one *extends* the clip in flight rather
than starting a new one, up to the maximum clip length.

---

## Lens selection

Reaching a specific lens on Android is genuinely device-dependent, so the app offers two routes and
labels each one with its 35mm-equivalent focal length and computed zoom factor:

1. **Direct camera ids** — every camera the platform lists, labelled `Ultra-wide 0.6x`, `Main 1.0x`,
   `Tele 5.0x` and so on. Most direct route; Samsung exposes its tele modules this way.
2. **Zoom presets on the main camera** (`Main @ 5.0x`) — drives `CONTROL_ZOOM_RATIO` and lets the
   phone's own HAL switch to the matching physical lens. **On Samsung hardware this is the most
   reliable way to actually land on the 5x tele**, so try it first if a direct id misbehaves.

Streaming straight from a physical sub-camera was a third route. It worked on few devices, capped the
resolution, and reported a sensor mounting that did not match the frames it produced — so it is gone.

There is also a continuous zoom slider on the viewfinder for anything in between.

---

## Video settings

Auto by default, with manual as an opt-in.

**Always available:** resolution (everything the selected lens reports), frame rate (filtered to what
that lens can sustain at that resolution), H.264 or HEVC, bitrate, audio on/off, stabilisation
on/off, exposure compensation.

**Manual controls — experimental, off by default.** One switch enables shutter speed, ISO, manual
focus distance and white balance. It is off by default on purpose: a wrong shutter or ISO ruins
*every* clip of a session, and not every lens honours every control. The app tells you when the
selected lens does not report `MANUAL_SENSOR` support rather than silently ignoring you.

Useful starting points for water sports: shutter **1/1000s or faster** to freeze spray, and **manual
focus pre-set on the kicker** so autofocus cannot hunt at the worst possible moment. There is a
"focus now" button on the viewfinder to lock autofocus before you walk away.

### Why not CameraX or an off-the-shelf library?

You asked whether a package already does this. For the camera settings alone, CameraX plus
`Camera2Interop` would get most of the way there. The blocker is the rolling buffer: this app needs a
raw `MediaCodec` input surface it can encode into continuously and mux out of retroactively, and
CameraX's `VideoCapture` deliberately owns its recording pipeline and will not hand that over. Since
Camera2 was needed anyway, it also gives unrestricted manual sensor control and direct lens
addressing for free. So: Camera2 directly, ML Kit for the AI, Media3/ExoPlayer for playback.

---

## Using it

1. Mount the phone. The app is **landscape** and keeps the screen on.
2. Pick your lens from the chips along the bottom (or Settings for the full list with details).
3. **Drag the orange box** onto the kicker — drag the middle to move it, a corner to resize. It is
   stored normalised, so it survives resolution and lens changes.
4. Optionally hit **focus now** to lock focus on the box.
5. Press the green **arm** button and go ride.
6. The screen blacks out 15 s after arming to save battery (`ARMED` stays visible). Tap to wake.
7. **Save now** writes a clip immediately, whatever the detector thinks — a manual safety net.
8. Open the gallery: each clip shows a **looping low-res preview of its trigger moment**, so you can
   spot the good one without opening anything.
9. Tap a clip to play it. It opens *on the moment*, not at the start. Playback speed goes down to
   0.25x for checking a trick. **Save to gallery** copies it to `Movies/KickerCam` where Photos and
   everything else can see it.

The HUD along the top shows resolution/fps/codec/bitrate, how many seconds are buffered, live
ISO/shutter/zoom read back from the sensor, and two activity meters — `box` and `bg`. Those two are
the tuning aid: you want `box` to spike when you come through and `bg` to stay flat. If `bg` is
lively, lower the sensitivity or shrink the box.

---

## Things worth knowing

- **Clips are private until exported.** They live in the app's own folder so they do not clutter your
  camera roll. Uninstalling the app deletes anything you have not exported.
- **The rolling buffer costs RAM**: roughly `bitrate ÷ 8 × (pre-roll + 2s)`. At 1080p60 / 40 Mbps and
  5 s pre-roll that is about 35 MB. Settings shows the live estimate. It is capped at 384 MB.
- **Heat and battery are the real limit**, not the app. Continuously encoding 1080p60 for half an hour
  on a tripod in the sun will get warm and drain fast. Dim-when-armed helps; a power bank helps more.
  If the phone throttles, drop to 1080p30 or lower the bitrate.
- **Three simultaneous streams** (preview + encode + detection) is the one thing a device can refuse.
  If configuration fails the app steps down automatically — first to 1080p, then as a last resort it
  disables automatic detection and tells you so, leaving *Save now* working. It never fails silently.
- **arm64 only.** The bundled ML native libraries are most of the APK, and shipping four ABIs
  quadrupled it for no benefit — every phone this targets is arm64.
- **Locked to landscape, and the viewfinder applies no rotation at all.** The camera hands over
  landscape frames and the app is landscape, so the only thing that has to be right is the shape of
  the view: it is given the stream's aspect ratio, which makes filling it a uniform scale, and what
  the frames do not cover stays black. This is what the `AutoFit` views do in Google's Camera2
  samples.

  It is a **SurfaceView**, not a TextureView, for one decisive reason: its buffer size is whatever
  `SurfaceHolder.setFixedSize` says. TextureView re-sets the buffer size to its own measured width and
  height on every layout, so the camera ends up streaming at a size the app never asked for and the
  device never advertised; the HAL substitutes whatever it does have, and a stream of one shape drawn
  in a view of another is a stretched picture that no amount of layout fixes.

- **The resolution list only ever offers one shape.** The largest size the camera advertises defines
  the sensor's shape, and only sizes matching it are listed — so choosing 4K instead of 1080p changes
  the pixel count and nothing else, and the viewfinder cannot end up a different shape from the
  recording. A request the camera does not advertise in that shape is refused rather than passed
  through, and resolves to the largest option up to 1080p. The old list mixed shapes, which is how the
  app came to ask for one the sensor does not stream.

  Above 1080p a camera may not manage preview, recording and detection at once. If configuration
  fails the app steps down one size, and if that is still too much it keeps the resolution and turns
  detection off, saying so on screen rather than failing silently. Bitrate does not follow the
  resolution automatically — 4K wants 60-100 Mbps where 1080p is happy at 40.

  Deriving the rotation from `SENSOR_ORIENTATION` and the display or the accelerometer was tried at
  length and produced, in turn, a stretched preview, a preview a quarter turn out, and one clamped
  into a square. On the device this was tested against, `SENSOR_ORIENTATION` did not agree with the
  frames actually delivered, so there was no angle to compute that would have been right. Showing the
  frames as they arrive has no such failure mode.
- **Audio** is buffered alongside the video and muxed into the clip. If the mic permission is denied,
  clips are simply silent.

  Audio has to be stamped in the camera's own time domain, and `SENSOR_INFO_TIMESTAMP_SOURCE` cannot
  be trusted to say which one that is — some devices advertise `REALTIME` while emitting monotonic
  timestamps. The two clocks differ by however long the phone has slept since boot, which is *hours*
  on a phone that has been alive for days, so getting it wrong does not cause a subtle sync error: it
  writes audio hours past the video and the file claims a duration of hours with one frozen frame. The
  clock is therefore measured from the first encoded frame rather than assumed, and any audio packet
  that is not plausibly near the video timeline is dropped rather than muxed.

### Not implemented

- **High-speed (120/240 fps slow motion).** Constrained high-speed sessions cannot carry a third
  stream, so AI detection could not run at the same time. Where a device advertises a high frame rate
  as a normal range it will appear in the frame-rate list and just work; true high-speed mode does
  not.
- No trimming or editing in the app — export and edit elsewhere.

---

## Building

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # ~47 MB, debug-signed
./gradlew testDebugUnitTest      # 26 unit tests
```

Requires JDK 17+ and an Android SDK with platform 35. Release builds are signed with the debug key so
they install without extra setup — replace `signingConfig` in `app/build.gradle.kts` before
distributing anything.

### Getting the APK onto a phone

Every push publishes both APKs to the rolling **`apk-dev`** prerelease, so you can download and
install straight from the phone's browser:

<https://github.com/HylkeJellema/Film-app/releases/tag/apk-dev>

Install **`kickercam-debug.apk`**. It is unminified, so nothing R8 could have stripped from the
bundled ML models; `kickercam-release.apk` is a third of the size but minified and unverified on
hardware. Both are signed with the standard Android debug key — personal sideloading, not
distribution. Enable "install unknown apps" for your browser first.

Over USB instead: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### Layout

| Path | What lives there |
|---|---|
| `camera/CameraCapabilities.kt` | Lens enumeration, 35mm-equivalent focals, supported sizes/rates/ranges |
| `camera/Camera2Session.kt` | Camera2 device + session, all capture controls |
| `capture/EncodedRing.kt` | The rolling buffer. Android-free and unit tested |
| `capture/ClipRecorder.kt` | Continuous encode, trigger, mux out with audio |
| `capture/CaptureEngine.kt` | Orchestration, fallback ladder, status |
| `detect/` | Motion detector, ML Kit wrappers, trigger debounce, coordinate mapping |
| `storage/` | Clip metadata sidecars, thumbnail strips, MediaStore export |
| `ui/` | Compose viewfinder, draggable box, settings, gallery, player |

`EncodedRing` is deliberately free of Android media types so its wrap-around and key-frame lookup can
be tested on the JVM — it is the one class where an off-by-one silently corrupts every clip the app
ever saves.
