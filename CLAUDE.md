# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo layout

Two independent projects share this root:

- `backend/` — FastAPI service (Python) backing the mobile app. The on-device app calls it directly.
- `android/` — Android app (Kotlin / Jetpack Compose) that pairs with HeyCyan smart glasses over BLE + WiFi Direct.
- `prd.md`, `sdd.md` — Product and design docs for the FYP. Treat as background, not as code-of-record.

The two halves are coupled only through the HTTP contract in `backend/models/schemas.py` ↔ `android/.../api/models/`. Changing a request/response shape requires editing both sides.

## Backend (`backend/`)

FastAPI app entrypoint is `main.py`, which wires two routers: `routers/analyze.py` (landmark identification) and `routers/restaurant.py` (restaurant identification + nearby search). All non-trivial work lives in `services/`:

- `services/vision.py` — the landmark call. Model is the `VISION_MODEL` constant (currently `gemini-3.5-flash-lite`; `gemini-2.5-flash` was ~7x slower on the free tier — see the comment above the constant for measurements). `SYSTEM_PROMPT` carries a Malacca disambiguation guide, deliberately framed as tie-breakers for confusable sites rather than an allowlist. The model states `LANDMARK:` and `CONFIDENCE:` header lines which `_parse_response` strips before the text is spoken; `_assess_confidence` / `_extract_landmark_name` survive only as fallbacks if the headers are missing.
- **The two paths use different models.** `_identify()` (no `landmark_context`) sends the photograph to `VISION_MODEL` with no search tool — recognition is a vision task the search tool cannot help with. `_follow_up()` sends the search tool to `FOLLOW_UP_MODEL` (`gemini-2.5-flash`) and **no image**, then falls back to an ungrounded `VISION_MODEL` call if grounding is refused.
  - Grounding is unavailable to this project on the 3.x models: `gemini-3.5-flash-lite`, `gemini-3.5-flash` and `gemini-flash-lite-latest` all return `429 RESOURCE_EXHAUSTED` within 0.2 s when a `google_search` tool is attached, while the identical ungrounded request succeeds in ~1 s. `gemini-2.5-flash` still grounds normally. The rejection is too fast to be congestion — it is the grounding allowance specifically.
  - The image is dropped on follow-ups because the landmark is already named in the prompt, which also forbids re-identifying it. Attaching it alongside the search tool cost ~45 s versus ~3 s without (measurements in the docstring) — enough silence to break the hands-free follow-up window.
- `services/places.py` — Google Places API (v1) wrapper. **Currently non-functional**: the project's trial billing expired, so Places returns `403 PERMISSION_DENIED`. Left in the tree deliberately as a record of the original design.
- `routers/restaurant.py` — still the *original* signage-OCR flow (`extract_restaurant_name` → Places lookup). It does not match the redesign described in `prd.md` F06 / `sdd.md` §5.3, which was specified but never implemented. Blocked by the same billing issue.

Conventions:
- Endpoints return `status: "success" | "error"` in the body even on caught failures (HTTP 200) — the Android client checks `status`, not the HTTP code. Keep that contract when adding endpoints.
- Blocking SDK calls (Gemini, requests) are wrapped in `asyncio.to_thread` inside routers.
- Image uploads are gated to JPEG/PNG and <5 MB. Reuse those checks if adding new image endpoints.
- Language is a `language` form field, currently `"en"` or `"ms"`. Both branches must be populated when adding user-visible strings.

Env vars (loaded from `backend/.env` via `python-dotenv`): `GEMINI_API_KEY`, `GOOGLE_PLACES_API_KEY` (unusable — billing expired), `APP_VERSION`. `.env` is gitignored and untracked; never paste keys into code or new files.

**Gotcha:** `load_dotenv()` does not override existing environment variables. A stale `GEMINI_API_KEY=YOUR_API_KEY` in the shell environment will silently win over `.env` and every Gemini call fails with `API key not valid`. Check `$env:GEMINI_API_KEY` before debugging further.

### Run / test

```bash
# from backend/
venv/Scripts/python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
# single test (no pytest config; tests are runnable scripts)
venv/Scripts/python tests/test_vision.py
```

The Android app reaches the backend at `BACKEND_BASE_URL` from `android/local.properties` — update that IP when running on a new network (phone and laptop must be reachable, e.g. Tailscale or same LAN).

## Android (`android/`)

Single-module app, package `com.malacca.guide`. Built with Gradle Kotlin DSL + Compose. Min SDK 29, target/compile SDK 36, Java 11. Compose BOM, Navigation Compose, Retrofit/OkHttp, CameraX, Room (declared, lightly used), Accompanist Permissions, Play Services Location, and the HeyCyan glasses SDK as a vendored AAR (`app/libs/glasses_sdk_20250723_v01.aar`).

### Architecture (one ViewModel, five screens)

`MainActivity` → `AppNavGraph` (in `ui/navigation/NavGraph.kt`) drives a linear flow:

`SPLASH → HOME → LISTENING → LOADING → RESULT`

A single `GuideViewModel` (`ui/viewmodel/GuideViewModel.kt`) holds **all** session state: language, mode (`LANDMARK` / `RESTAURANT`), transcript, `questionAudio` (the recorded WAV), captured bitmap, current GPS, plus separate result/error fields for `analyze`, `restaurant`, and `nearby`. The same ViewModel instance is hoisted at the NavGraph level and shared across screens — don't create per-screen ViewModels.

Two distinct flows share that ViewModel:
- **Landmark** (working): `analyze()` → POST `/analyze` with image **and** the recorded audio. Follow-ups reuse `landmarkContext` (the previous landmark name) to tell the backend not to re-identify. If the model reports `low`/`unknown` confidence and the glasses are connected, the ViewModel retakes the shot at full resolution over WiFi Direct and asks once more — but deliberately *not* on backend/AI errors, which a sharper image cannot fix.
- **Restaurant** (blocked): `analyzeRestaurant()` → POST `/restaurant`. Fails at the backend because Places billing expired.

Both compress the captured `Bitmap` to JPEG at quality 85 before upload.

### Voice pipeline (`voice/`)

Android's `SpeechRecognizer` was **removed**. It repeatedly opened the phone's built-in mic despite the routing request, and returned `NO_MATCH` on Bluetooth audio later verified clean by inspecting the recorded waveform. The app now owns capture end to end:

- `VoiceRecorder` — `AudioRecord` with `setPreferredDevice()` pinned to the glasses mic, writes WAV, and decides itself when speech ends (noise-floor-relative threshold, `SPEECH_CONFIRM_FRAMES` consecutive frames, trailing-silence stop). The audio is uploaded; **Gemini does the transcription**, so there is no STT component.
- `GlassesAudioRouter` — pins capture and playback to the glasses. `setCommunicationDevice()` returning `true` only means the request was accepted, so `activate()` polls until the system actually reports the glasses, and re-checks every session because the route is torn down when playback ends.
- `Earcon` — the cue tone marking the follow-up window.
- `TtsManager` — `USAGE_VOICE_COMMUNICATION` so playback follows the pinned route.

**Hands-free follow-up:** after a *successful* answer, `ResultScreen` re-pins the route, plays the cue tone, waits `BEEP_SETTLE_MS` so the tone isn't recorded, then listens for `FOLLOW_UP_LISTEN_MS`. Speech continues the conversation; silence ends it. No tone after an error.

### Glasses pipeline (`ble/`)

The glasses are the camera. `HeyCyanApplication.onCreate` initialises the Oudmon BLE SDK (`BleOperateManager`, `LargeDataHandler`) and registers two receivers: `MyBluetoothReceiver` (SDK broadcasts via LocalBroadcastManager) and `BluetoothReceiver` (system Bluetooth state). `GlassesManager` is a singleton that owns scan/connect/capture state and exposes `connectionState` and `scanResults` as `StateFlow`s.

- **Discovery**: BLE scan filtered by name prefix (`W610`, `G300`, `G3`, `M01`, `QCY` — see `GLASSES_NAME_PREFIXES`).
- **Device events** (`cmdType 115`, payload starts at byte 6). Decoded by observation, not documented by the vendor:
  - `0x01` — **photo captured**, followed by a little-endian image count. This is what the wearer's temple button raises, confirmed by watching the count increment by one per press. `GlassesManager` emits `GlassesEvent.ShutterPressed` and `NavGraph`'s handler starts a session.
  - `0x02` — image data prepared, with a byte count. **Not a capture event.**
  - `0x03` / `0x0A` — the glasses' own voice recording start/end, bracketing audio on `ACTION_GPT_UPLOAD`. Not consumed.
  - `0x05` — battery. Note `syncBattery()` answers via `addBatteryCallBack()`, a *separate* registry from the notify listener; register there or the reply is silently dropped.
- **Capture path — two-phase, and the SDK hides this.** `getPictureThumbnails()` is not one call: the first request makes the glasses *generate* the image (they answer "0 packets"), notify `0x02` then reports readiness, and only a **second** request streams the data as ~1KB chunks. **The caller must concatenate them** — the final callback carries only the last chunk, which decodes to null on its own. Skipping the thumbnail-size command first makes the glasses report zero packets forever. Typical result: 50–80KB, 1088×816, ~5s.
- **WiFi Direct path**: `GlassesWifiManager` (phone as group owner) + `GlassesMediaDownloader` is the full-resolution fallback, used on low-confidence results. Slower and less reliable — it takes a fresh photo and needs the glasses' HTTP server to be discoverable.

If the glasses pipeline is broken, `CameraManager` (CameraX) is the phone-camera fallback used from `HomeScreen`. The UI hides the on-screen capture/follow-up buttons while the glasses are connected, and restores them when they aren't.

### Build / run

```bash
# from android/  — use the wrapper, not a system gradle
./gradlew assembleDebug
./gradlew installDebug          # device must be attached via adb
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
# single instrumentation/unit test
./gradlew :app:testDebugUnitTest --tests "com.malacca.guide.SomeTest.method"
```

`android/local.properties` (gitignored, but currently present) carries `sdk.dir` and `BACKEND_BASE_URL`. `BACKEND_BASE_URL` flows into Kotlin via `BuildConfig` — bumping it requires a rebuild, not just a re-run.

### Conventions

- New screens go under `ui/screens/` and are wired into `NavGraph.kt` with a `ROUTE_*` constant.
- New API endpoints: add the DTO under `api/models/`, the Retrofit method in `ApiService.kt`, and a ViewModel function that handles `isSuccessful` + `errorBody` exactly like the existing `analyze()` / `analyzeRestaurant()`.
- All BLE/WiFi work must run via `GlassesManager` / `GlassesWifiManager` — the Oudmon SDK is not thread-safe and the singletons serialise calls and own the receivers.
- TTS is one shared `TtsManager` instance created in `AppNavGraph` and disposed there; pass it down rather than constructing new ones in screens.
