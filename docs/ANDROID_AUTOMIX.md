# Android Automix: listening-first transitions

Android's `CrossfadeController` owns an `AutomixSessionPlanner`, which caches
`planTransition(natural = true)` for one pair of analysis snapshots.
This aims for unobtrusive, adaptive listening transitions, not a DJ effect on
all tracks. It is not an implementation of Apple's proprietary algorithm.
The web player is unchanged. Other Kotlin callers do not opt into the natural
policy by default; the shared confidence and fixed-phrase correctness fixes
also apply to them.

## Decisions

- Use the outgoing **tail** grid and incoming **head** grid where available.
  Full-track analysis stores the tail tempo in its top-level BPM; reusing that
  for an incoming intro can match the wrong tempo. Low-confidence head analysis
  must not inherit confidence from a steady outro. Downbeats must cover the
  actual overlap: a head-only pass cannot authorize an unmeasured outro mix.
- Keep an existing beat-aligned blend intact only when both grids authorize
  beatmatching. Incoming stretch is limited to 0.94–1.06 in natural mode; the
  audible outgoing song is not retimed.
- Prefer a short fade for harmonic clashes, substantial simultaneous singing,
  excessive intro/outro skipping, or a missed blend start. Do not clamp an
  aligned cue independently of its fade: that would silently lose alignment.
- A retained blend is 2–12 seconds long, starts within the final 30 seconds,
  skips no more than 8 seconds beyond the incoming audible start (and never
  cues beyond 30 seconds), and discards at most 6 seconds of outgoing content.
- Fallbacks end at the measured content end and enter at the audible opening:
  2.5 seconds for conflicting vocals, 5 seconds with measured masks and no
  detected vocal conflict, or 3.5 seconds with insufficient vocal evidence.
  Short incoming tracks further cap the overlap. These fallbacks have no
  beatmatching, bass swap, echo, or DJ sweep. Existing vocal-separation rendering
  can still soften a vocal conflict.
- Missing/malformed vocal masks are not proof of an instrumental passage.
  Whole-song vocal likelihood supplies conservative protection when needed.
- Speech/live/short-track guards and explicit sequential-album gapless plans
  remain in place. Manual crossfade is independent of the natural policy.

The manual crossfade slider is hidden while Automix is enabled. Its old value
no longer decides Automix's fallback length.

## Adaptive search engine

`AdaptiveMixEngine` compares the legacy seed with generated bar-aligned routes:
up to six exit bars, three overlap lengths (1/2/4 bars), and five entry bars.
The hard cap is 96 candidates (the current generator produces at most 91).
Each candidate is sampled at 32 positions on both track timelines. The search:

- Penalizes simultaneous vocals, arrangement-energy mismatches, skipped content,
  missing measurement coverage, and unsuitable duration; rewards phrase starts.
- Derives generated playback rates from observed local bar intervals, respecting
  half/double-time relationships and the natural policy's stretch limits.
- Rejects erratic local grids and requires measured vocal coverage for new
  routes. Missing masks cannot invent an instrumental passage.
- Gives every candidate to the existing natural safety policy before selection.
  A search may rescue a colliding seed by using a clean instrumental intro or
  a shorter overlap, but cannot override the intro/outro/stretch safety rails.
- Also compares shorter fallback fades to remove brief collisions that a
  whole-window vocal average can miss. It never adds a deeper cue or earlier exit.

This is a deterministic, bounded search, not a new ML model or replacement PCM
renderer. Energy comparison is relative arrangement energy, **not** inferred
LUFS gain; existing PCM loudness normalization/headroom remains in charge.

The session cache uses analysis snapshot identity and queue/duration context.
A new analysis result or changed next item invalidates it. Playback ticks do
not repeat the search; seeking past the chosen start retimes that same exit
instead of chasing a new route. The cache retains only one pair and is cleared
when the controller is released. `adaptive-search:N` in the plan's policy
reasons reports the number of candidates evaluated.

## Live handoff animation

During a real Automix overlap, the existing **Mixing** label now has a small
handoff mark. Tap it to open the expanded mix view:

- Two album sleeves exchange focus with artwork-tinted light and a progress bead.
- Opacity/focus follows the renderer's sampled outgoing/incoming faders, not a
  random equalizer. A short progress interpolation smooths audio-state updates.
- The existing beat clock supplies a subtle pulse; pausing stops the clock.
- The reduced-animation setting disables sleeve translation, rotation and
  scaling; the actual progress and levels remain readable.
- The caption distinguishes a beat-matched blend from an ordinary smooth fade,
  and explicitly reports paused/ended states. New captions have Arabic strings.
- The popup scrolls on short screens; the persistent scrubber keeps its layout.

The UI lives in shared Compose player code, so both hosts can render it; the
new search/session integration is enabled by the Android controller. Fallback
crossfades are visible as real two-deck overlaps, but are not labelled beatmatched.
No Android device or screenshot-render test was available in the sandbox.

## Runtime safety

While the next decoder is not ready, leave the audible outgoing spectrum open;
only pre-position the silent incoming filter. A late ready event is reconciled
with `TransitionPlan.forPlaybackStart`: after 150 ms of lateness, discard the
lost beat alignment and fit a natural-speed fade into the remaining time.
Below 750 ms remaining, abandon the attempt and leave ordinary queue playback
in control. Gapless album handoffs are exempt from this minimum.

The ordinary planner previously allowed near-tempo blending when only one grid
had weak confidence and could stretch even an unmatched filtered transition.
Both decisions now follow the shared beatmatch policy. Fixed-phrase plans also
select a blend, rather than an unconditional filter sweep, for matched grids.

## Verification

`NaturalAutomixTest` exercises retained blends, stretch limits, one-sided weak
grids, missing/stale analysis, intro/outro preservation, vocal and key clashes,
head-vs-tail tempo, late decoder timing, and the existing guards.
`AdaptiveMixEngineTest` covers alternative-route rescue, energy scoring,
measurement coverage, bounded deterministic search, local tempo/octave matching,
fallback refinement, and cache invalidation/seek behavior. `MixPresentationTest`
covers the fader-driven animation math and explicit renderer mode. Existing
planner, phrase-bar, spoken/live, and loop-mix suites cover the legacy paths.
The Check workflow now includes shared JVM tests alongside Android unit tests.
The manually dispatched Android APK workflow also runs these suites before
building; its successful verification run is recorded below.

With the project's normal JDK 17 / Android SDK / Gradle setup:

```sh
./gradlew :shared:jvmTest
./gradlew :app:testDevDebugUnitTest :app:assembleDevDebug
```

Device listening is still required; pure planner tests cannot verify Media3
buffering, Bluetooth latency, DSP audibility, or the quality of inferred beats.
Before release, compare the same queue with Automix off/on, covering:

1. Close-tempo instrumental intros/outros (a blend should remain available).
2. Two vocal choruses, unrelated tempi, and clashing keys (short fallback).
3. Different intro/outro tempi on one recording.
4. Slow network, an uncached next track, and seeking into the last seconds.
5. Pause/resume, manual skip, shuffle, repeat-one, and queue edits while armed.
6. Wired/Bluetooth output and both low-end and high-end Android devices.

Sandbox verification: 98 pure Kotlin test methods passed (including 59 new
regression cases across this change), compiled with the repository's Kotlin
2.3.20 version and JVM 17 bytecode target and executed through an isolated
assertion runner on a JVM. Changed Android/Compose sources were also parsed
with Kotlin's PSI syntax checker, and resource XML was checked for validity.
This did not run the Gradle/JUnit test tasks, type-check the Compose UI, or
compile the Android controller.
The Gradle build attempt failed while downloading Gradle 8.11.1 because the
sandbox cannot reach its distribution host; an Android SDK is also absent.
No APK was built and no on-device audio test has been performed.


## Playlist transition studio — draft stage, not stem separation

Android playlist detail pages with at least two loaded songs now expose
**Customize transitions · Draft editor**. Albums, artists, and device folders
do not expose this entry point. The editor uses the original playlist order,
not the temporary filtered/alphabetical display order. It does not initiate
playback, download audio, run inference, or alter the global Automix switch.

The fullscreen Compose surface has paired artwork, pair navigation, timing
sliders, four layer selectors, and editable outgoing/incoming design envelopes.
It borrows the paired-track/layer-editor interaction style, not Spotify artwork
or branding. No visual comparison or screenshot test was performed. Curves are
labelled **design envelopes, not audio waveforms**. There is no pretend audio
preview or fake preparation-progress button.

A user can design 2–12 seconds of overlap, 0–6 seconds of outgoing ending trim,
and a 0–30 second incoming cue; actual source durations must authorize the
windows before any future preparation. Each layer has its own outgoing fade
completion and incoming fade onset. Default vocal envelopes do not overlap;
user edits are still only drafts, not a promise that arbitrary envelopes sound
good. The curves represent gains, not a vocal or instrument isolation effect.

Drafts are versioned JSON in private SharedPreferences, keyed by SHA-256 of
length-framed playlist and ordered entry identities. Remote set-video IDs
separate duplicate entries; when unavailable, track ID plus occurrence is the
fallback identity. Removing/re-adding/reordering indistinguishable duplicate
entries cannot guarantee the same identity without stable server entry IDs.
Only the currently loaded part of a continued playlist is editable. Changes to
adjacency do not cause a saved design to apply to a different pair. Unused
entries are retained until application data is cleared; no automatic pruning
or separate draft-library management is implemented yet.

Writes run off the main thread and report success only after commit. Unsaved
edits block pair navigation, dismiss asks before discarding, and the draft plus
its baseline survive activity recreation through a Compose saver. Draft prefs
are excluded from cloud backup and device transfer. English and Arabic UI
resources are supplied; the normal theme supplies colors.

`PlaylistTransitionDraftTest` adds 13 pure tests for timing sanitation, curve
bounds/endpoints, default vocal handoff, independent stems, source window
validation, playlist/pair scoping, and preparation safety rules. Draft-store
Android persistence, Compose type checking, process-death restoration, RTL,
TalkBack, and actual phone interaction have NOT been verified here.

### Local-only separation: remaining integration

The requested four-stem on-device feature is **not complete**. There is no new
separator, prepared-stem cache, background worker, audio preview, or custom
stem-render playback integration in this stage. Saving a draft does not change
what is heard. Existing `VocalTracker` reduces an open-unmix vocals estimate to
a vocal-presence curve; it is not a four-stem reconstruction path.

`LocalPreparationConditions` is a pure, tested fail-closed gate specification,
**not an installed scheduler**. A future implementation must check a compatible
local model, accessible local source windows, charger, idle device/no playback,
battery at least 30%, thermal status and a positive storage estimate. It must
recheck these while processing, keep concurrency at one, support cancellation,
prepare only transition windows plus model context, cache by source content,
model version and window, and mix prepared PCM instead of running ML during
listening. Nothing uploads audio or silently uses a server.

References inspected (not vendored or added as dependencies):

- https://github.com/VertexSelection/automix-engine — Python reference using
  CUE-DETR, Demucs, external FFmpeg/Rubber Band and local-file preparation;
  LGPL-2.1 repository license is not blanket clearance for all model weights
  or dependencies. No performance measurements were run.
- https://github.com/sevagh/demucs-android — archived Kotlin/C++ example,
  evidence of a possible native path, not a maintained drop-in integration.
- https://github.com/sevagh/demucs.onnx — moves STFT/iSTFT out of the graph;
  highlights the need for model-specific DSP and tensor contracts, not just
  loading an arbitrary ONNX file. The app already depends on ONNX Runtime.

Before enabling preparation, choose and verify distributable four-target
weights, sample rate, input/output shapes and stem order; validate STFT/iSTFT
reconstruction, normalization and overlap-add; measure peak memory, latency,
heat and audible artifacts on Android. Do not equate successful Kotlin logic
tests with successful ML inference or good sounding transitions.


## Successful Android CI build — 2026-11-24

Source commit: `07c09032a95dd5db51f592ca7499987400fcd563` on
`arena/e92e9d7c-bitchord` (not merged to main).

Run: https://github.com/fadi5678908/BitChord/actions/runs/45507085162

GitHub Actions reports **success** for both:

- `./gradlew :shared:jvmTest :app:testDevDebugUnitTest`
- `./gradlew :app:assembleDevDebug`

This supersedes the earlier *full Android build unverified* limitation: the
Android/Compose sources now passed actual CI compilation and APK assembly.
The isolated 98-method Kotlin suite was also rerun locally and passed.
No Android device, emulator, listening, screenshot or UI interaction test was
run. Full CI logs and XML reports could not be downloaded into the sandbox
because the Actions artifact/log storage hosts are unreachable here; no total
CI test count is asserted. Step success was verified using the Actions API.

Artifact: `bitchord-dev-debug` (508,015,040 bytes compressed, multiple ABI APKs)
https://github.com/fadi5678908/BitChord/actions/runs/45507085162/artifacts/14973722432

The dev flavor uses `com.dev.bitchord` / **BitChord Dev**, separate from the
normal `com.music.bitchord` install. For most current Android phones, choose the
APK containing `arm64-v8a`; the universal APK is the architecture-independent
fallback. Do not uninstall an existing dev install to resolve signing errors
without first preserving its data. The artifact download may require signing
in to GitHub and will expire according to the repository artifact retention.

### Phone acceptance checklist (pending)

1. Install BitChord Dev and open a playlist with at least two loaded songs.
2. Open **Customize transitions · Draft editor**. Verify Arabic/RTL, scrolling,
   artwork and pair navigation. Album and artist pages must not show the entry.
3. Change overlap/cue and the vocal handoff, save, close and reopen. The values
   should persist. Verify a different playlist does not inherit this design.
4. Edit without saving, attempt to close, and check discard confirmation.
   Rotate while editing and verify the unsaved design is retained.
5. Enable Automix, then play the end of a pair of tracks. Inspect the Mixing
   handoff animation and expanded view; compare against Automix disabled.
6. Pause/resume, use reduced animation, seek near the end, and test a slow
   incoming decoder. Listen for dropouts, unnatural speed changes or early cuts.
7. Remember that editor drafts intentionally do not affect audio yet: four-stem
   separation, custom preview and prepared-stem playback remain unimplemented.
