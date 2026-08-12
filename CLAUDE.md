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

- `services/vision.py` — Gemini 2.5 Flash call for landmark ID. The `SYSTEM_PROMPT` constant carries a hand-curated Malacca landmark reference guide; the model is grounded with Google Search via `services/search.py`. Confidence is heuristically derived from response text (`_assess_confidence`).
- `services/places.py` — Google Places API (v1) wrapper. `extract_restaurant_name` uses Gemini to OCR signage; `get_restaurant_details` and `get_nearby_restaurants` hit Places. Distance is computed locally with `_haversine_m`.
- `routers/restaurant.py` builds the spoken summary string in EN/MS using the `_PRICE_LABELS`, `_spoken_main`, `_nearby_item` helpers — TTS-friendly plain text, no markdown.

Conventions:
- Endpoints return `status: "success" | "error"` in the body even on caught failures (HTTP 200) — the Android client checks `status`, not the HTTP code. Keep that contract when adding endpoints.
- Blocking SDK calls (Gemini, requests) are wrapped in `asyncio.to_thread` inside routers.
- Image uploads are gated to JPEG/PNG and <5 MB. Reuse those checks if adding new image endpoints.
- Language is a `language` form field, currently `"en"` or `"ms"`. Both branches must be populated when adding user-visible strings.

Env vars (loaded from `backend/.env` via `python-dotenv`): `GEMINI_API_KEY`, `GOOGLE_PLACES_API_KEY`, `APP_VERSION`. The repo currently has real keys committed in `backend/.env` — do not paste them into code or new files.

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

A single `GuideViewModel` (`ui/viewmodel/GuideViewModel.kt`) holds **all** session state: language, mode (`LANDMARK` / `RESTAURANT`), transcript, captured bitmap, current GPS, plus separate result/error fields for `analyze`, `restaurant`, and `nearby`. The same ViewModel instance is hoisted at the NavGraph level and shared across screens — don't create per-screen ViewModels.

Two distinct flows share that ViewModel:
- **Landmark**: `analyze()` → POST `/analyze`. Follow-ups reuse `landmarkContext` (the previous landmark name) to tell the backend not to re-identify.
- **Restaurant**: `analyzeRestaurant()` → POST `/restaurant`, then optionally `findNearby()` → POST `/restaurant/nearby` excluding the just-identified place.

Both compress the captured `Bitmap` to JPEG at quality 85 before upload.

### Glasses pipeline (`ble/`)

The glasses are the camera. `HeyCyanApplication.onCreate` initialises the Oudmon BLE SDK (`BleOperateManager`, `LargeDataHandler`) and registers two receivers: `MyBluetoothReceiver` (SDK broadcasts via LocalBroadcastManager) and `BluetoothReceiver` (system Bluetooth state). `GlassesManager` is a singleton that owns scan/connect/capture state and exposes `connectionState` and `scanResults` as `StateFlow`s.

- **Discovery**: BLE scan filtered by name prefix (`W610`, `G300`, `G3`, `M01`, `QCY` — see `GLASSES_NAME_PREFIXES`).
- **Capture path**: BLE-only for thumbnails. Trigger the shutter, wait for the `0x02` notify event (`GlassesDeviceNotifyListener.parseData`), then `LargeDataHandler.getPictureThumbnails` returns the JPEG bytes. `THUMBNAIL_SIZE = 0x06` selects the largest size (best for Gemini).
- **WiFi Direct path**: `GlassesWifiManager` (phone as group owner) + `GlassesMediaDownloader` (pulls `/files/media.config` then individual JPEGs) is the full-resolution fallback. The phone learns the glasses' IP via a BLE → IP bridge.

If the glasses pipeline is broken, `CameraManager` (CameraX) is the phone-camera fallback used from `HomeScreen`.

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
