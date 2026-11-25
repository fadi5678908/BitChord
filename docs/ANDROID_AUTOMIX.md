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

## Follow-up: streamed incoming tracks could stay on crossfade

A phone report said Automix sounded like ordinary crossfade. Inspection found
that `analyzeHead` published a beat grid but dropped its already-derived energy
and DSP vocal evidence; `region(... deriveFeatures = true)` also did not run the
vocal model. This is the common streamed-incoming path before a complete file
is cached. Adaptive candidates require vocal evidence on both sides, so they
could not rescue a legacy entry rejected by the natural intro-preservation rule.
This is a reproduced code defect, not proof of the sole cause on that phone.

The head pass now retains bounded energy/low-band curves, runs the existing
vocal tracker on the same decoded opening, and publishes model vocal and level
windows. `withOpeningEvidence` aligns source coordinates and masks, falls back
to scaled measured DSP values where the model ends, leaves genuinely unknown
values non-finite, and never extends the opening into an unmeasured outro. No
new model or four-stem reconstruction is introduced. This adds one existing
vocal inference to a provisional head analysis, not to each playback tick.

`OpeningEvidenceTest` adds eight regression tests. A full planner fixture
reproduces **EQUAL_POWER / natural-preserve-intro** with a grid-only incoming
head, then produces **DJ_BLEND / adaptive-search:61** after its measured opening
evidence is retained. This does not relax confidence, tempo, intro, key or
vocal safety gates. All 106 isolated Kotlin methods passed locally.

Runtime diagnostics now prefer the final natural/late-buffer fallback reason,
show the selected route even when the transition marker is hidden, and report
late decoder downgrades using the actual armed plan. The fallback caption says
**Crossfade · fallback**, rather than implying every visible handoff is a
beatmatched mix. The planned search still legitimately chooses fallback for
incompatible or unmeasured pairs. Device listening with the reported song pair
is necessary to distinguish those cases from remaining bugs.

Follow-up CI verification succeeded for source commit
`28c8d28d44c9cad3e37f4bef14c49b94121fc704`:
https://github.com/fadi5678908/BitChord/actions/runs/45513588772

The shared/Android unit-test step, dev APK assembly, and artifact upload all
report success. Artifact `bitchord-dev-debug`:
https://github.com/fadi5678908/BitChord/actions/runs/45513588772/artifacts/14976693247

This verifies compilation/build, not phone listening. This APK workflow does
not restore a stable debug signing key, so Android may reject an in-place
update from a previous CI dev APK signed by a different generated key. Do not
recommend uninstalling the user's dev app without preserving its data. The
production app remains a different package.

## Playlist timing activation and inline handoff (latest)

Supersedes the draft-only editor stage above. The editor now exposes only the
working controls: outgoing handoff START, incoming source START and overlap
length (2–12 seconds). The highlighted timeline section spans the overlap;
these are not fabricated audio waveforms. Positions can be chosen across both
songs, with +/-1 second adjustments. The outgoing song is fully removed at
outgoing start + overlap. This explicitly skips its remaining ending by user
choice; the automatic planner retains its own intro/outro safety limits.

Apply & save persists an enabled per-pair natural-speed equal-power transition.
It does not claim beatmatching or stem separation. Legacy drafts remain disabled
until explicitly enabled. Custom timing takes precedence over automatic planning
and works even when global Automix is off. The editor offers playback from the
outgoing song after saving, not a false rendered-preview button. Its playback
button starts the playlist in normal order. Actual source duration is validated
again by the playback controller; an unknown/invalid duration cannot schedule a
custom mix. Late readiness can shorten the overlap but never pass its chosen
end. Seeking beyond it does not restart the old transition.

Runtime lookup requires matching BROWSE playlist source IDs, context queue tiers,
ordered entry IDs, no radio provenance and no shuffle. Stable set-video IDs
separate duplicate tracks. A unique-song fallback matches the original editor
keys; ambiguous duplicates without stable entry IDs cannot be applied. The
process-local store is shared by editor and playback; startup loading and commits
run on IO, while playback reads an in-memory snapshot. Party, repeat-one, video
and bit-perfect playback exclusions still take precedence.

The reported stale `late-buffer` verdict had a lifecycle defect: finish did not
clear `smartFadeActive` and `planInFlight`, so stats could reuse the previous
transition against a new playhead. Finish now clears both and the published plan.
Diagnostics are additionally scoped to ordered queue-entry IDs and cleared on
seeks/media transitions. Until audio duration is available they show waiting,
not the previous verdict. This fixes stale reporting, not an assertion that all
buffering or automatic fallback cases are solved.

The portrait and landscape artwork slots now mount a non-interactive automatic
handoff overlay during an audible two-deck overlap. It shows both sleeves,
actual fader-driven progress, and a custom/beatmatched/fallback distinction.
No Mix tap or popup is required. Reduced-motion and paused-state behavior is
inherited from the existing handoff component. It disappears with the real
transition state rather than a timer. Other minimal player layouts are unchanged.

`PlaylistCustomPlaybackTest` adds 13 cases for absolute entry/exit scheduling,
disabled migration, full-source bounds, late/seek behavior, identity scoping,
stale-diagnostic clearing, and preserving the custom renderer flag on waveform
updates. All 119 isolated Kotlin tests passed. On-device listening, UI and
retention tests still need the newly built APK; no new stem separator is present.

Selected custom windows are also checked against both prepared decoder durations.
The automatic one-third incoming-length cap is not applied to an explicit user
window; it would otherwise silently shorten a valid transition near a song ending.

Latest verification: GitHub Actions run 45518544008 succeeded for source commit
`845619c75c661ec0d986decc081c5a865b566a9a` (shared/Android unit-test task step,
full dev APK assembly, and artifact upload). This includes the simplified editor,
custom scheduling, decoder-bound validation, diagnostic lifecycle reset, and
portrait/landscape inline animation compilation.

- Run: https://github.com/fadi5678908/BitChord/actions/runs/45518544008
- APK bundle: https://github.com/fadi5678908/BitChord/actions/runs/45518544008/artifacts/14979194479
- Artifact: `bitchord-dev-debug`, 508,102,817 bytes compressed, multiple ABI APKs.

Suggested phone check: use a playlist in normal order, enable a pair, set the
outgoing start to 0:20, incoming start to 0:10, overlap to 6 seconds, then Apply
& save and Play from the outgoing song. At normal speed, the intended overlap
is outgoing 0:20–0:26 with incoming 0:10–0:16. The inline handoff should appear
without tapping Mix. Verify persistence, pause/resume and changing to another
playlist. This is a pending acceptance test, not an observed phone result.

## Source-waveform transition studio (supersedes the timing-only editor above)

The editor now decodes bounded 24-second viewports of the **playback URI** using
its ordinary resolver/cache, never an analysis-matched sibling rendition. Audio
is decoded on the phone; normal source fetching is allowed, but nothing is sent
to a separator or an external processing server. One decode at a time; waveform
arrays have 480 bins, PCM is streamed, cancelled views abort their decode. A
source/codec failure shows an explicit retry message, not synthetic bars.

Orange and blue decks expose draggable source selections and white master-gain
handles. Fine adjustments expose accessible source/envelope sliders and one-second
steps. The volume curves are independent from the reserved stem designs. They
are persisted in the playlist profile and carried through TransitionPlan into
CrossfadeController's actual faders; legacy profiles default to equal power.
There is still **no four-stem separator**. Frequency waveforms are not stems.

Preview is a real service command through the regular two-deck controller. It
replaces the current queue with this pair, starts two seconds before the chosen
outgoing position and stops two seconds after the incoming overlap. Unsaved
settings are scoped to an opaque editor owner and never persisted by preview.
It uses the service's audio-focus/notification/noisy handling. Stop, pair change,
close and disposal cancel only that owner's audition, not a subsequently started
user queue. Cast, parties, bit-perfect output, video-origin tracks, an active
version audition and after-track sleep are refused. Timeouts, missed transitions
and playback errors must be reported as failures rather than a successful demo.

Two concrete defects found by inspection:

1. `queuedDurationMs` read only URI `d=`. File/content/download URIs have no such
   query, although the queued item retains its duration in metadata. Explicit
   custom plans therefore returned null before Media3 prepared the next track.
   Metadata is now preferred, with URI fallback and overflow checks. Decoder
   durations still authorize the final region before it can sound.
2. `CacheMediaDataSource.getSize` opened a source without marking it open, making
   its finally-close a no-op. Size probes could leak a source and leave it open
   for the next read. Successful probes now close, including on failed opens.

New shared regressions cover metadata-only runtime planning, duration fallback,
invalid/overflow hints, master-envelope propagation (including late starts),
legacy equal power and invalid handle sanitization. Build/test results below do
not constitute phone listening or gesture validation.

Phone acceptance (pending):
- Download two tracks, open their playlist pair, set out=0:20, in=0:10, overlap=6s.
- Preview must begin at out=0:18, overlap out=0:20–0:26 and in=0:10–0:16,
  then pause at approximately in=0:18. No tap on Mix should be needed.
- Drag the outgoing white handle to half-way and incoming handle past half-way:
  the preview must audibly change (possibly a deliberate silent middle).
- Apply, leave the editor and play the original playlist normally with Automix
  off: the same saved timing/envelopes must play; another playlist/shuffle must not.
- Verify retry on an unavailable source; pause/resume, close mid-fade, change pair,
  reopen, rotate, and start another queue then close the old editor. No orphan deck
  or late stop may affect the new queue.

Verified studio source: `7a8e13993cdfb37d3546b4898f1ebf2e5d1ad3a6`.
GitHub Actions [45617206193](https://github.com/fadi5678908/BitChord/actions/runs/45617206193)
succeeded: shared/Android unit-test task step, full dev APK assembly, APK upload
(6m23s build job). Artifact `bitchord-dev-debug`: ID `15024005498`, 508,256,569
bytes compressed. English/Arabic XML parsed successfully and all 34 editor string
references were checked in both locales. The phone acceptance checks above are
still **pending**, not implied by CI success.

The direct universal test APK is published separately, extracted without a
rebuild, under release `automix-test-7a8e139`:
https://github.com/fadi5678908/BitChord/releases/download/automix-test-7a8e139/BitChord-Dev-Automix-universal.apk

## Install failure investigation

Published APK verification run
[45621025011](https://github.com/fadi5678908/BitChord/actions/runs/45621025011)
passed ZIP integrity and Android `apksigner verify`; manifest is
`com.dev.bitchord`, versionCode 36, minSdk 26, targetSdk 36.
This validates the published bytes, not the user's downloaded copy or phone.

The two delivered APKs have **different signing certificates**:
- `automix-test-845619c`: SHA-256 `098e83655b0397b5442faddd194cc5c2cf23f9b4d97a5b9e536dc57a285e8607`
- `automix-test-7a8e139`: SHA-256 `157c59c64f8a7943f280cd200796192ba1af66993418f1bc8e2fb347ec3fe602`

A normal in-place update from the former to the latter is therefore not
supported. Do not tell users to uninstall without backing up app data. The
user's exact installer message remains needed to confirm their failure mode;
no device install or repair has been observed. These CI builds currently use
runner-generated debug signing, not a stable persistent development key.
A stable signing solution is still needed for future updates; it cannot recover
the previous private key or retroactively make this APK an authorized update.

The registered Android workflow now accepts `verify_release` and optional
`compare_release`, delegating to the reusable verification workflow. Verification
downloads the actual published APKs read-only and does not rebuild/replace them.
An initial comparison run failed because recent build-tools use SDK-scoped
signer labels instead of `Signer #1`; the corrected parser passed in the run
linked above. Creating a new workflow file on a non-default branch does not
register it for direct dispatch, so the existing Android workflow is the entry.
