# System Design Document (SDD)
# HeyCyan Malacca Tourist Guide App

**Version:** 2.1  
**Date:** August 2026  
**Author:** [Your Name]  
**Status:** Draft  
**Related:** prd.md

> **Revision note (v2.1).** Updated to match the implementation. Restaurant mode is
> now a voice-driven recommendation rather than signage identification (§5.3); the
> voice pipeline records audio and lets Gemini interpret it instead of using a
> separate speech-to-text service (§2.2); and §10 has been rewritten with the BLE
> protocol that the HeyCyan glasses actually use, which differs substantially from
> the vendor documentation.

---

## 1. System Overview

This document describes the technical architecture, folder structure, API contracts,
database schema, frontend screen design, and build order for the HeyCyan Malacca
Tourist Guide app.

The system has three layers:
- **HeyCyan smart glasses** — hardware input/output (camera, mic, speaker) via BLE
- **Android app (Kotlin)** — BLE bridge, STT, TTS, UI
- **Python backend (FastAPI)** — vision AI, web search, database writes

**Important build rule:** Backend must be fully tested before Android frontend begins.
Android frontend must be fully tested with phone camera before glasses BLE is added.

---

## 2. Tech Stack

### 2.1 Backend
| Component | Technology |
|---|---|
| Language | Python 3.11+ |
| Framework | FastAPI |
| Vision + LLM | Gemini 2.5 Flash (`gemini-2.5-flash`) |
| Speech understanding | Gemini 2.5 Flash — the recorded question is sent as an audio part alongside the image; no separate speech-to-text service |
| TTS | Android `TextToSpeech`, routed to the glasses speaker |
| Web search | Google Search grounding, enabled as a Gemini tool |
| Database | Firebase Firestore |
| Image storage | Firebase Cloud Storage |
| Hosting | Google Cloud Run |
| Environment | Python venv + `.env` file |

### 2.2 Android App
| Component | Technology |
|---|---|
| Language | Kotlin |
| UI framework | Jetpack Compose |
| Navigation | Jetpack Navigation Compose |
| HTTP client | Retrofit 2 + OkHttp |
| Camera (Phase 2) | CameraX |
| BLE (Phase 3) | HeyCyan / Oudmon SDK (`glasses_sdk_20250723_v01.aar`) |
| Audio capture | `AudioRecord` with `setPreferredDevice` pinned to the glasses microphone, written to WAV and uploaded. Replaced Android `SpeechRecognizer`, which chose the phone microphone regardless of routing and returned `NO_MATCH` on clean Bluetooth audio — see §10.4 |
| Audio routing | `AudioManager.setCommunicationDevice` (API 31+) / Bluetooth SCO, pinning capture and playback to the glasses |
| TTS | Android `TextToSpeech` with `USAGE_VOICE_COMMUNICATION` so playback follows the pinned route |
| State management | ViewModel + StateFlow |
| Local cache | Room Database |
| Auth | Firebase Auth (anonymous) |
| Min SDK | API 26 (Android 8.0) |

### 2.3 External APIs
| API | Purpose | Key needed |
|---|---|---|
| Google Gemini API | Vision, LLM, STT, TTS | `GEMINI_API_KEY` |
| Google Custom Search | Web search for current info | `GOOGLE_SEARCH_API_KEY` + `SEARCH_ENGINE_ID` |
| Google Places API (New) | Restaurant rating, reviews, nearby search | `GOOGLE_PLACES_API_KEY` |
| Firebase | Firestore + Cloud Storage | Service account JSON |

---

## 3. Architecture Diagram (Text)

```
[HeyCyan Glasses]
    |  BLE (photo data, audio)
    v
[Android App — Kotlin]
    |  captures image + voice
    |  STT via Gemini Live API (WebSocket)
    |  sends HTTP POST to backend
    |
    v
[FastAPI Backend — Python]        <——> [Gemini 2.5 Flash] (vision + LLM)
    |                             <——> [Google Search API] (current info)
    |  saves session
    v
[Firebase Firestore]   [Firebase Cloud Storage]
    (session metadata)     (raw images)

[FastAPI Backend]
    |  returns JSON response text
    v
[Android App]
    |  TTS (speak response)
    v
[HeyCyan Glasses Speaker]
```

---

## 4. Folder Structure

### 4.1 Backend (`malacca-backend/`)
```
malacca-backend/
├── main.py                  # FastAPI app entry point
├── requirements.txt         # Python dependencies
├── .env                     # API keys (never commit to git)
├── .gitignore
├── Dockerfile               # For Cloud Run deployment
├── services/
│   ├── __init__.py
│   ├── vision.py            # Gemini vision call (landmark ID)
│   ├── search.py            # Google Search API wrapper
│   ├── places.py            # Google Places API wrapper (restaurant details + nearby search)
│   └── database.py          # Firestore read/write
├── models/
│   ├── __init__.py
│   └── schemas.py           # Pydantic request/response models
└── tests/
    ├── test_vision.py
    └── test_api.py
```

### 4.2 Android App (`malacca-android/`)
```
malacca-android/
├── app/
│   ├── libs/
│   │   └── QCBleSdk.aar              # HeyCyan SDK (add manually in Phase 3)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/malacca/guide/
│   │       ├── MainActivity.kt        # Single activity, hosts nav graph
│   │       ├── ui/
│   │       │   ├── theme/
│   │       │   │   ├── Color.kt       # App color palette
│   │       │   │   ├── Theme.kt       # MaterialTheme setup
│   │       │   │   └── Type.kt        # Typography
│   │       │   ├── navigation/
│   │       │   │   └── NavGraph.kt    # All screen routes
│   │       │   ├── screens/
│   │       │   │   ├── SplashScreen.kt     # Launch screen
│   │       │   │   ├── HomeScreen.kt       # Main tourist UI
│   │       │   │   ├── ListeningScreen.kt  # Active voice input UI
│   │       │   │   ├── LoadingScreen.kt    # Waiting for AI response
│   │       │   │   ├── ResultScreen.kt     # Show landmark + AI response
│   │       │   │   └── HistoryScreen.kt    # Past sessions list (Phase 4)
│   │       │   └── components/
│   │       │       ├── PulseButton.kt      # Animated mic button
│   │       │       ├── WaveformView.kt     # Audio waveform animation
│   │       │       ├── LandmarkCard.kt     # Result card component
│   │       │       └── ErrorBanner.kt      # Error message component
│   │       ├── ble/
│   │       │   ├── GlassesManager.kt       # Scan, connect, capture, device events
│   │       │   ├── ConnectionState.kt      # BLE connection state enum
│   │       │   ├── MyBluetoothReceiver.kt  # SDK broadcasts (onServiceDiscovered)
│   │       │   ├── BluetoothReceiver.kt    # System Bluetooth state
│   │       │   ├── GlassesWifiManager.kt   # WiFi Direct group (full-res fallback)
│   │       │   └── GlassesMediaDownloader.kt # HTTP pull from the glasses
│   │       ├── api/
│   │       │   ├── ApiClient.kt            # Retrofit + OkHttp setup
│   │       │   ├── ApiService.kt           # Endpoint definitions
│   │       │   └── models/
│   │       │       └── ApiModels.kt        # Request/response data classes
│   │       ├── voice/
│   │       │   ├── VoiceRecorder.kt        # AudioRecord -> WAV, own silence detection
│   │       │   ├── GlassesAudioRouter.kt   # Pins capture/playback to the glasses
│   │       │   ├── Earcon.kt               # Cue tone when the follow-up window opens
│   │       │   └── TtsManager.kt           # Text-to-speech manager
│   │       ├── camera/
│   │       │   └── CameraManager.kt        # CameraX wrapper (Phase 2)
│   │       ├── data/
│   │       │   ├── SessionRepository.kt    # Single source of truth
│   │       │   └── local/
│   │       │       ├── AppDatabase.kt      # Room DB setup
│   │       │       ├── SessionDao.kt       # Room queries
│   │       │       └── SessionEntity.kt    # Room table definition
│   │       └── viewmodel/
│   │           └── MainViewModel.kt        # All screen state + logic
│   └── build.gradle
├── build.gradle
└── google-services.json      # Firebase config (never commit to git)
```

---

## 5. API Endpoints

### 5.1 `POST /analyze`
Accepts an image and a text query. Returns an AI-generated tourist guide response.

**Request** (`multipart/form-data`):
```
image           : File    (JPEG or PNG, max 5MB)
query           : string  (default: "What is this building? Tell me about it.")
language        : string  (default: "en")
landmark_context: string  (optional — set on follow-ups so the landmark is not re-identified)
audio           : File    (optional WAV of the spoken question, max 10MB)
```

When `audio` is present it takes precedence over `query`: Gemini receives the image
and the recording together and answers the spoken question directly, with no separate
transcription step.

**Response** (`application/json`):
```json
{
  "status": "success",
  "landmark_name": "Stadthuys",
  "response": "This is the Stadthuys, the oldest surviving Dutch building in Asia...",
  "session_id": "abc123",
  "confidence": "high"
}
```

`landmark_name` and `confidence` are stated by the model itself on two header lines,
which the backend parses out and strips before returning `response`. They are not
inferred from the prose: an earlier heuristic searched for the phrase "cannot
identify", which "cannot **confidently** identify" does not contain, so refusals were
returned as high confidence. `landmark_name` is `null` when the model reports UNKNOWN.

**Error response:**
```json
{
  "status": "error",
  "message": "Could not identify the landmark. Please try a closer angle.",
  "session_id": null
}
```

---

### 5.2 `GET /health`
Health check endpoint for Cloud Run.

**Response:**
```json
{ "status": "ok", "version": "1.0.0" }
```

---

### 5.3 `POST /restaurant`
Accepts a **recorded spoken request** plus the tourist's GPS position, and returns
nearby places matching what they asked for. **No image is involved** — see PRD §3.4
for why this replaced signage identification.

Two-stage pipeline, deliberately splitting language understanding from facts:

1. **Gemini** listens to the audio and turns it into a structured search
   (`{ query, open_now, price_ceiling, radius_m, rank_by }`). It does **not** name
   any restaurants — an LLM asked to do that will invent plausible ones.
2. **Google Places** `searchText` runs that search, biased to the GPS position, and
   returns real places. Every name, rating, price and opening time comes from here.

**Request** (`multipart/form-data`):
```
audio             : File    (WAV, 16 kHz mono, max 10MB) — the spoken request
query             : string  (optional text fallback when no audio was captured)
lat               : float   (device GPS latitude)
lng               : float   (device GPS longitude)
language          : string  (default: "en")
exclude_place_ids : string  (optional, comma-separated — used when refining)
```

**Response** (`application/json`):
```json
{
  "status": "success",
  "understood_as": "cafe serving pancakes, open now",
  "alternatives": [
    {
      "name": "The Daily Fix Cafe",
      "rating": 4.4,
      "review_count": 2130,
      "price_level": 2,
      "distance_m": 180,
      "opening_hours": "Open now · Closes 6 PM",
      "cuisine": "Cafe",
      "place_id": "ChIJyyyyyyyyyyyyyyyy"
    }
  ],
  "response": "I found three cafes serving pancakes near you. The Daily Fix Cafe, rated 4.4 stars from over 2,000 reviews, about 180 metres away, moderately priced, open until 6 PM...",
  "session_id": "abc123"
}
```

`understood_as` echoes back Gemini's interpretation of the request. It is spoken only
when confidence is low ("I looked for cafes serving pancakes — is that right?"), and
is otherwise used for debugging misheard questions.

**Error response:**
```json
{
  "status": "error",
  "message": "I couldn't find anywhere serving that nearby. Try asking for something more general.",
  "session_id": null
}
```

**Ranking.** "Best" combines rating with review count so that 5.0 from 3 reviews does
not outrank 4.5 from 800. Places with fewer than a minimum number of reviews are
ranked below established ones rather than excluded.

**Empty results.** A narrow request ("pancakes") may match nothing within the radius.
The backend widens the search and says so, rather than reporting failure.

---

### 5.4 `POST /restaurant/nearby`
Returns up to 3 restaurants or cafes near the tourist's current GPS location, with no
spoken preference. Used when the tourist asks something open-ended ("where should I
eat?") or when `/restaurant` needs a fallback because the request could not be
interpreted. Unchanged from v2.0 apart from its role.

**Request** (`application/json`):
```json
{
  "lat": 2.1944,
  "lng": 102.2501,
  "exclude_place_id": "ChIJxxxxxxxxxxxxxxxx",
  "language": "en"
}
```

**Response** (`application/json`):
```json
{
  "status": "success",
  "alternatives": [
    {
      "name": "Nancy's Kitchen",
      "rating": 4.5,
      "price_level": 2,
      "distance_m": 87,
      "opening_hours": "Open now · Closes 9 PM",
      "cuisine": "Peranakan",
      "place_id": "ChIJyyyyyyyyyyyyyyyy"
    },
    {
      "name": "Hoe Kee Chicken Rice",
      "rating": 4.2,
      "price_level": 1,
      "distance_m": 134,
      "opening_hours": "Open now · Closes 8 PM",
      "cuisine": "Malaysian, Chinese",
      "place_id": "ChIJzzzzzzzzzzzzzzzz"
    }
  ],
  "response": "Here are 3 nearby alternatives. Nancy's Kitchen, rated 4.5 stars, about 87 metres away, serving Peranakan food. Hoe Kee Chicken Rice, rated 4.2 stars, 134 metres away..."
}
```

---

### 5.5 `GET /sessions/{tourist_id}`
Retrieve past sessions for a tourist (Phase 4).

**Response:**
```json
{
  "sessions": [
    {
      "session_id": "abc123",
      "timestamp": "2026-04-29T10:30:00Z",
      "landmark_name": "Stadthuys",
      "query": "What is this building?",
      "response": "This is the Stadthuys...",
      "image_url": "https://storage.googleapis.com/..."
    }
  ]
}
```

---

## 6. Database Schema (Firestore)

### Collection: `sessions`
```
sessions/
  {session_id}/
    tourist_id    : string   (anonymous Firebase UID)
    timestamp     : datetime
    landmark_name : string   (e.g. "Stadthuys")
    query         : string   (what the tourist asked)
    response      : string   (AI answer)
    image_url     : string   (Firebase Cloud Storage URL)
    confidence    : string   ("high" | "medium" | "low" | "unknown")
    language      : string   (e.g. "en")
    location      : geopoint (optional, if GPS available)
```

### Collection: `landmarks` (Phase 4 — pre-seeded reference data)
```
landmarks/
  {landmark_id}/
    name          : string
    aliases       : array<string>
    description   : string
    opening_hours : string
    ticket_price  : string
    image_ref     : string   (reference image URL for fallback matching)
    coordinates   : geopoint
```

---

## 7. Environment Variables

### Backend `.env`
```
GEMINI_API_KEY=your_gemini_api_key_here
GOOGLE_SEARCH_API_KEY=your_search_key_here
GOOGLE_SEARCH_ENGINE_ID=your_cx_id_here
FIREBASE_CREDENTIALS_PATH=./firebase-service-account.json
CLOUD_STORAGE_BUCKET=your-bucket-name.appspot.com
APP_VERSION=1.0.0
```

### Android `local.properties` (never commit)
```
BACKEND_BASE_URL=http://192.168.1.x:8000   # local dev (same WiFi as laptop)
# BACKEND_BASE_URL=https://your-app.run.app  # production
GEMINI_API_KEY=your_gemini_api_key_here
```

---

## 8. Gemini API Usage per Feature

| Feature | Model | API Type | Notes |
|---|---|---|---|
| Landmark identification | `gemini-2.5-flash` | REST (multimodal) | Stable, production-safe |
| LLM answer generation | `gemini-2.5-flash` | REST | Same call as vision |
| Voice input | `gemini-2.5-flash` | REST (audio part) | The recording is sent with the image; Gemini answers the spoken question directly. No separate STT service — Android's on-device recogniser returned `NO_MATCH` on clean Bluetooth audio |
| Request interpretation (restaurant) | `gemini-2.5-flash` | REST (audio part) | Turns the spoken request into a structured Places search. Never names restaurants itself |
| Voice output (TTS) | Android `TextToSpeech` | on-device | Routed to the glasses speaker; no cloud TTS needed |
| Web search grounding | Built into Gemini tool use | REST | Enable `google_search` tool |

---

## 9. Frontend Design (Android — Phase 2 & 3)

### 9.1 Screen Flow Diagram
```
App launch
    |
    v
[SplashScreen]  (2 seconds, logo + tagline)
    |
    v
[HomeScreen]  ← ← ← ← ← ← ← ← ← ← ←
    |                                  |
    | Tourist taps mic button          | Back / Done
    v                                  |
[ListeningScreen]                      |
    |                                  |
    | Voice captured, camera fires     |
    v                                  |
[LoadingScreen]  (AI is thinking...)   |
    |                                  |
    | Response received                |
    v                                  |
[ResultScreen]  ——————————————————————
    |
    | Tourist taps "History" (Phase 4)
    v
[HistoryScreen]
```

---

### 9.2 Screen Details

---

#### Screen 1 — SplashScreen
**File:** `ui/screens/SplashScreen.kt`  
**Purpose:** App launch screen shown for 2 seconds, then auto-navigates to HomeScreen.

**Components:**
- App logo (centred)
- App name: "HeyCyan Guide"
- Tagline: "Your AI guide to Malacca"
- Background: dark teal (`#0D3B33`)

**Behaviour:**
- Auto-navigate to HomeScreen after 2000ms using `LaunchedEffect`
- No back button

**Code notes:**
```kotlin
LaunchedEffect(Unit) {
    delay(2000)
    navController.navigate("home") {
        popUpTo("splash") { inclusive = true }
    }
}
```

---

#### Screen 2 — HomeScreen
**File:** `ui/screens/HomeScreen.kt`  
**Purpose:** Main screen the tourist sees. Central button to start interaction.

**Components:**
```
┌─────────────────────────────────┐
│  [Battery icon]    [History icon]│  ← top bar
│                                 │
│  ┌──────────┐  ┌──────────────┐ │
│  │ LANDMARK │  │  RESTAURANT  │ │  ← mode toggle (segmented control)
│  └──────────┘  └──────────────┘ │     default: LANDMARK
│                                 │
│      "Point your glasses        │
│       at a landmark"            │  ← instruction text (changes per mode)
│                                 │
│         ┌─────────┐             │
│         │   MIC   │             │  ← large pulsing circle button
│         │  BUTTON │             │     (PulseButton component)
│         └─────────┘             │
│                                 │
│   "Tap and ask HeyCyan          │
│    about what you see"          │  ← sub-instruction
│                                 │
│  [BLE status indicator]         │  ← bottom: "Glasses connected ✓"
│                                 │     or "Using phone camera"
└─────────────────────────────────┘
```

**State shown from ViewModel:**
- `bleConnected: Boolean` → show glasses status at bottom
- `isLoading: Boolean` → disable button while loading
- `appMode: AppMode` → `LANDMARK` or `RESTAURANT` (controls which endpoint is called)

**On mic button tap, or a press of the button on the glasses:**
- If `appMode == LANDMARK` → capture a photo (glasses BLE, or phone CameraX as
  fallback), then navigate to `ListeningScreen` → `POST /analyze`
- If `appMode == RESTAURANT` → **no photo is taken**. Fetch GPS, navigate straight to
  `ListeningScreen` → `POST /restaurant`. This is what makes restaurant mode faster
  than landmark mode: it skips the ~6s Bluetooth image transfer entirely.

The glasses button is handled at the navigation-graph level rather than inside a
screen, so it works while the wearer is looking at a landmark rather than the phone.
It always starts a **new** subject, in either mode; follow-ups are spoken, never
pressed (see §9.2a).

**Error states:**
- No internet → show `ErrorBanner("No internet connection")`
- Camera permission denied → show `ErrorBanner("Camera permission needed")`

---

#### Screen 3 — ListeningScreen
**File:** `ui/screens/ListeningScreen.kt`  
**Purpose:** Shown while the tourist is speaking. Gives visual feedback that voice is being recorded.

**Components:**
```
┌─────────────────────────────────┐
│                                 │
│                                 │
│       "I'm listening..."        │  ← animated text (fade in/out)
│                                 │
│    ~~~~~~~~~~~~~~~~~~~~         │
│    ~~~~ [WAVEFORM] ~~~~         │  ← WaveformView component
│    ~~~~~~~~~~~~~~~~~~~~         │     (animated audio bars)
│                                 │
│       [STOP button]             │  ← tourist can tap to stop early
│                                 │
│                                 │
└─────────────────────────────────┘
```

**Behaviour:**
- Auto-navigate to LoadingScreen when STT detects end of speech
- Tourist can tap STOP to manually end recording
- Waveform animates based on microphone amplitude
- If STT returns empty string → show toast "I didn't catch that, try again"
  and navigate back to HomeScreen

**Code notes:**
```kotlin
// WaveformView: use Canvas in Compose, animate bar heights with LaunchedEffect
// listening state managed in MainViewModel.uiState
```

---

#### Screen 4 — LoadingScreen
**File:** `ui/screens/LoadingScreen.kt`  
**Purpose:** Shown while the backend is processing the image + query. Keeps tourist engaged.

**Components:**
```
┌─────────────────────────────────┐
│                                 │
│                                 │
│      [Rotating logo / spinner]  │  ← animated
│                                 │
│    "Identifying landmark..."    │  ← cycling messages every 1.5s:
│                                 │     1. "Identifying landmark..."
│                                 │     2. "Searching for details..."
│                                 │     3. "Almost there..."
│                                 │
│      [thumbnail of photo]       │  ← small preview of captured image
│                                 │
└─────────────────────────────────┘
```

**Behaviour:**
- Not dismissible (tourist must wait)
- If backend takes > 10 seconds → auto-navigate back to HomeScreen with error:
  `ErrorBanner("Connection timeout. Please try again.")`
- Loading messages rotate every 1500ms using `LaunchedEffect` + `rememberInfiniteTransition`

---

#### Screen 5 — ResultScreen
**File:** `ui/screens/ResultScreen.kt`  
**Purpose:** Show the landmark name and AI response. TTS plays automatically on arrival.

**Components:**
```
┌─────────────────────────────────┐
│  [Back arrow]                   │  ← back to HomeScreen
│                                 │
│  ┌───────────────────────────┐  │
│  │  [Captured image preview] │  │  ← LandmarkCard component
│  │                           │  │
│  │  Stadthuys                │  ← landmark name (large, bold)
│  │  ★ High confidence        │  ← confidence badge
│  └───────────────────────────┘  │
│                                 │
│  ┌───────────────────────────┐  │
│  │ "This is the Stadthuys,   │  │  ← AI response text
│  │  the oldest surviving     │  │     (scrollable)
│  │  Dutch building in Asia.  │  │
│  │  Built in 1650..."        │  │
│  └───────────────────────────┘  │
│                                 │
│  [🔊 Replay]  [Ask follow-up]  │  ← action buttons
│                                 │
│  [Ask another landmark]         │  ← back to HomeScreen
└─────────────────────────────────┘
```

**Behaviour (Landmark mode):**
- TTS starts automatically when screen appears (`LaunchedEffect` on screen entry)
- "Replay" button → re-trigger TTS
- "Ask follow-up" button → return to ListeningScreen with same image context
- "Ask another landmark" → navigate back to HomeScreen
- Confidence badge colours:
  - high → green
  - medium → amber
  - low / unknown → red with message "Try a clearer angle next time"

**Behaviour (Restaurant mode):**
```
┌─────────────────────────────────┐
│  [Back arrow]                   │
│                                 │
│  ┌───────────────────────────┐  │
│  │  [Captured image preview] │  │
│  │                           │  │
│  │  Jonker 88           ★4.3 │  ← restaurant name + rating
│  │  $ · Malaysian            │  ← price level + cuisine
│  │  Open now · Closes 10 PM  │  ← opening hours
│  └───────────────────────────┘  │
│                                 │
│  ┌───────────────────────────┐  │
│  │ "Jonker 88 is rated 4.3   │  │  ← AI spoken response (scrollable)
│  │  stars. Known for cendol  │  │
│  │  and chicken rice ball..."│  │
│  └───────────────────────────┘  │
│                                 │
│  [🔊 Replay]  [Find Nearby]    │  ← "Find Nearby" calls POST /restaurant/nearby
│                                 │
│  [Scan another restaurant]      │  ← back to HomeScreen (restaurant mode)
└─────────────────────────────────┘
```
- "Find Nearby" button → calls `POST /restaurant/nearby` with current GPS + `exclude_place_id`
- Nearby results replace the current result card with a list of 3 alternatives
- TTS speaks the alternatives response automatically

**Data received from ViewModel:**
```kotlin
data class LandmarkResult(
    val landmarkName: String,
    val response: String,
    val confidence: String,   // "high" | "medium" | "low" | "unknown"
    val imageBitmap: Bitmap?,
    val sessionId: String
) : AppUiState()

data class RestaurantResult(
    val restaurantName: String,
    val rating: Float,
    val reviewCount: Int,
    val topReview: String,
    val priceLevel: Int,        // 1 = $, 2 = $$, 3 = $$$
    val openingHours: String,
    val cuisine: String,
    val response: String,
    val placeId: String,
    val imageBitmap: Bitmap?,
    val sessionId: String
) : AppUiState()

data class NearbyResult(
    val alternatives: List<PlaceAlternative>,
    val response: String
) : AppUiState()
```

---

### 9.2a Hands-free follow-up (F16)

Follow-ups are spoken, not tapped. After every successful answer:

```
TTS finishes speaking
    |
    v
Cue tone through the glasses (Earcon)
    |
    v
Wait ~450ms so the tone is not recorded    <-- the glasses mic sits inches
    |                                          from the glasses speaker
    v
Listen for up to 4 seconds
    |
    +-- speech heard  --> follow-up: reuse the image and landmarkContext,
    |                     skip the capture, go to LoadingScreen
    |
    +-- silence       --> stay on ResultScreen, do nothing
```

Design decisions worth recording:

- **The button always means "new subject".** An earlier design had the button mean
  "follow-up" while a result was on screen, which broke the moment the tourist walked
  to a different landmark and pressed it expecting a photo. Speaking to continue and
  pressing to restart are two actions that never compete.
- **No cue tone after a failure.** A backend or AI error still returns a result
  object; offering a follow-up about a failure is worse than staying silent.
- **Speech detection is level-based**, calibrated against the room's noise floor with
  an absolute minimum. On this hardware a spoken question peaks near 27000 while
  Bluetooth idle hiss reaches 2500, so a permissive threshold made the cue tone
  trigger a follow-up by itself.

---

#### Screen 6 — HistoryScreen (Phase 4)
**File:** `ui/screens/HistoryScreen.kt`  
**Purpose:** List of all past landmark interactions for this tourist.

**Components:**
```
┌─────────────────────────────────┐
│  [Back]   "My Discoveries"      │
│                                 │
│  ┌───────────────────────────┐  │
│  │ [thumb] Stadthuys         │  │  ← session card
│  │         29 Apr · 10:30am  │  │
│  │         "What is this..." │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │ [thumb] A Famosa          │  │
│  │         29 Apr · 11:15am  │  │
│  │         "Tell me about..." │ │
│  └───────────────────────────┘  │
│                                 │
│  (scrollable list)              │
└─────────────────────────────────┘
```

**Behaviour:**
- Loaded from Room DB (offline-first)
- Synced from Firestore on app open
- Tap a session card → navigate to ResultScreen with saved data (read-only)
- Empty state: "No discoveries yet. Go explore Malacca!"

---

### 9.3 ViewModel — State Management

**File:** `viewmodel/MainViewModel.kt`  
All screen state lives in one ViewModel to avoid prop-drilling across screens.

```kotlin
// App mode enum
enum class AppMode { LANDMARK, RESTAURANT }

// UI state sealed class
sealed class AppUiState {
    object Idle : AppUiState()
    object Listening : AppUiState()
    object Loading : AppUiState()
    data class LandmarkResult(
        val landmarkName: String,
        val response: String,
        val confidence: String,
        val imageBitmap: Bitmap?,
        val sessionId: String
    ) : AppUiState()
    data class RestaurantResult(
        val restaurantName: String,
        val rating: Float,
        val reviewCount: Int,
        val topReview: String,
        val priceLevel: Int,
        val openingHours: String,
        val cuisine: String,
        val response: String,
        val placeId: String,
        val imageBitmap: Bitmap?,
        val sessionId: String
    ) : AppUiState()
    data class NearbyResult(
        val alternatives: List<PlaceAlternative>,
        val response: String
    ) : AppUiState()
    data class Error(val message: String) : AppUiState()
}

// ViewModel exposes:
val uiState: StateFlow<AppUiState>
val bleConnected: StateFlow<Boolean>
val appMode: StateFlow<AppMode>               // current mode toggle state
val sessions: StateFlow<List<SessionEntity>>  // for HistoryScreen

// Key functions (as implemented in GuideViewModel):
fun setMode(mode: AppMode)                    // switch landmark / restaurant mode
fun storeBitmap(bitmap: Bitmap)               // photo pulled from the glasses
fun updateQuestionAudio(wav: ByteArray?)      // recorded question, null if silent
fun updateLocation(lat: Double, lng: Double)  // GPS for restaurant mode
fun analyze()                                 // POST /analyze  (image + audio)
fun analyzeRestaurant()                       // POST /restaurant (audio + GPS)
fun findNearby(excludePlaceId: String)        // POST /restaurant/nearby
fun clearResultForFollowUp()                  // keeps landmarkContext, clears the answer
fun clearForNewSession()                      // full reset
```

**Note on naming.** The implementation uses a single `GuideViewModel` rather than
`MainViewModel`, and exposes Compose `mutableStateOf` fields directly rather than a
sealed `AppUiState`. The state machine described above proved unnecessary once the
navigation graph itself carried the flow.

**Low-confidence retry.** After `/analyze` returns, if the model reports `low` or
`unknown` confidence and the glasses are connected, the ViewModel retakes the photo
at full resolution over WiFi Direct and asks once more before publishing a result.
It deliberately does *not* retry on backend or AI errors — a sharper image cannot fix
an outage, and the retry costs a further six seconds and an extra capture.

---

### 9.4 App Theme & Colors

**File:** `ui/theme/Color.kt`

```kotlin
// Primary palette — inspired by Malacca heritage colours
val MalaccaRed    = Color(0xFFB22222)   // Dutch colonial red (Stadthuys)
val MalaccaTeal   = Color(0xFF0D6E6E)   // Peranakan tile teal
val MalaccaGold   = Color(0xFFD4A017)   // Sultanic gold accent
val BackgroundDark = Color(0xFF121212)  // Dark background (easy outdoors)
val SurfaceDark   = Color(0xFF1E1E1E)
val TextPrimary   = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val SuccessGreen  = Color(0xFF4CAF50)
val WarningAmber  = Color(0xFFFFC107)
val ErrorRed      = Color(0xFFE53935)
```

**Design decisions:**
- Dark theme by default — easier to read outdoors in Malacca sun
- Large tap targets (minimum 56dp) — tourists walking and tapping
- High contrast text — readability in bright outdoor light
- Font: use default Material3 Roboto (no custom font needed for MVP)

---

### 9.5 Navigation Setup

**File:** `ui/navigation/NavGraph.kt`

```kotlin
// Routes
const val ROUTE_SPLASH   = "splash"
const val ROUTE_HOME     = "home"
const val ROUTE_LISTENING = "listening"
const val ROUTE_LOADING  = "loading"
const val ROUTE_RESULT   = "result"
const val ROUTE_HISTORY  = "history"

// NavGraph composable connects all screens
// Single NavController passed from MainActivity
// ViewModel shared across all screens via viewModel()
```

---

### 9.6 Error States Reference

Every screen must handle these gracefully (never crash):

| Error | Screen | What tourist sees |
|---|---|---|
| No internet | HomeScreen | ErrorBanner at bottom: "No internet. Check your connection." |
| Mic permission denied | HomeScreen | ErrorBanner: "Microphone permission needed. Go to Settings." |
| Camera permission denied | HomeScreen | ErrorBanner: "Camera permission needed. Go to Settings." |
| STT heard nothing | ListeningScreen | Toast: "I didn't catch that. Try again." → back to Home |
| Backend timeout (>10s) | LoadingScreen | Auto-navigate Home: "Connection timeout. Try again." |
| Low confidence landmark | ResultScreen | Amber badge + "Try a clearer angle next time" |
| Unknown landmark | ResultScreen | Red badge + "I couldn't identify this landmark." |
| BLE disconnected | HomeScreen | Bottom indicator updates to "Glasses disconnected" |
| Backend 500 error | LoadingScreen | Navigate Home: "Something went wrong. Try again." |
| Spoken request not understood | ListeningScreen | TTS: "I didn't catch that." Falls back to an open-ended nearby search rather than failing |
| Location permission denied | HomeScreen (restaurant mode) | ErrorBanner: "Location access needed to find nearby restaurants." |
| No match for the stated preference | ResultScreen | Backend widens the search and says so: "I couldn't find pancakes nearby, but here are some cafes." |
| No nearby results at all | ResultScreen | TTS: "I couldn't find any open restaurants nearby right now." |
| Glasses audio route unavailable | ListeningScreen | Falls back to the phone microphone; the interaction still works, but is not hands-free |
| AI service overloaded | LoadingScreen | TTS speaks the error; **no cue tone**, so no follow-up is offered |

---

## 10. BLE Communication (HeyCyan / Oudmon SDK)

The vendor AAR ships with no documentation or sources. Everything below was
established by decompiling `glasses_sdk_20250723_v01.aar` with `javap` and by
observing live traffic on a W610, and differs from the class names assumed in v2.0.

### 10.1 Key SDK classes (Android)
```kotlin
BleOperateManager            // BLE connect / disconnect, init
BleScannerHelper             // BLE scan with ScanWrapperCallback
LargeDataHandler             // Feature commands + the large-data channel
GlassesDeviceNotifyListener  // Unprompted device events (subclass and register)
QCBluetoothCallbackCloneReceiver // SDK broadcasts, incl. onServiceDiscovered
```

`LargeDataHandler.initEnable()` **must** be called from `onServiceDiscovered()`.
Until it runs, the glasses accept writes but never reply.

### 10.2 Connection flow
```
1. Scan, filtering on name prefixes (W610, G300, G3, M01, QCY)
2. BleOperateManager.connectDirectly(address)
3. onServiceDiscovered() -> LargeDataHandler.initEnable()
4. Register a GlassesDeviceNotifyListener via addOutDeviceListener()
5. syncTime, syncDeviceInfo, syncBattery
6. openBT() + syncClassicBluetooth() to bring up the classic-BT audio radio
```

Step 6 matters: the BLE link carries **control data only**. Audio travels over a
separate classic Bluetooth A2DP/HFP connection, which stays off until requested.

### 10.3 Device notify frame format (`cmdType 115 / 0x73`)
```
[0]=0xBC magic  [1]=0x73 action  [2..3]=payload length LE
[4..5]=checksum [6]=event code   [7..]=event payload
```

| Event | Payload | Meaning |
|---|---|---|
| `0x01` | uint32 LE image count | A photo was captured on the glasses |
| `0x02` | uint32 LE byte count | Image data prepared and about to stream |
| `0x03` | — | The glasses' own voice recording started |
| `0x05` | uint8 percent | Battery level |
| `0x0A` | — | Voice recording finished |

Verified against a battery frame: `BC 73 03 00 54 61 | 05 4E 00` → `0x4E` = 78%.

`0x01` is what the wearer's button press raises, confirmed by watching the image
count increment by exactly one per press. The app treats it as "start a session".

Events `0x03`/`0x0A` bracket roughly 19 kbps of audio streamed on
`ACTION_GPT_UPLOAD` (0x59) — the glasses' own assistant shipping voice out for
someone else to transcribe. **The app does not consume this channel**; it captures
audio over the HFP microphone instead. It remains a fallback if SCO proves
unreliable.

### 10.4 Photo retrieval — a two-phase exchange

`LargeDataHandler.getPictureThumbnails()` looks like a single call but is not, and
this is not documented anywhere:

```
1. Set the thumbnail size    -> glassesControl([0x02,0x01,0x06,size,size,0x02])
2. First getPictureThumbnails() -> the glasses answer "0 packets available"
                                   and begin GENERATING the image (~2s)
3. Device notify 0x02 arrives   -> image ready, with its byte count
4. Second getPictureThumbnails() -> the data finally streams
5. ~1KB chunks arrive via the callback, success=false for each,
   success=true on the last one — each carrying only its own slice
```

Two consequences the SDK's shape actively hides:

- **Chunks must be concatenated by the caller.** The final callback contains only
  the last packet. Keeping just that yields a truncated JPEG that decodes to null.
- **Only one thumbnail callback can be registered at a time.** A second
  `getPictureThumbnails()` call replaces the first, so issuing one from the notify
  listener while a pull is in flight silently redirects the data.

Skipping step 1 makes the glasses report zero packets forever, and the SDK then
never invokes the callback at all — the request simply times out.

Typical result: ~50–80 KB, 1088×816, about 5 seconds end to end.

### 10.5 Audio routing

`AudioManager.setCommunicationDevice()` returning `true` means only that the request
was accepted, not that the route is live — SCO needs 0.5–2s more. The app polls
`communicationDevice` until it reports the glasses, and re-checks before every
session because the system tears the route down when the previous user of the audio
input releases it.

Playback has an automatic fallback (media audio goes to A2DP when the glasses are
paired), so hearing the answer through the glasses does **not** prove routing works.
Only capture is diagnostic.

### Required Android permissions (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 11. Build Order (Step-by-Step)

Follow this order strictly. Do not move to the next phase until ALL success checks
in the current phase pass.

---

### Phase 1 — Backend Foundation
**Tool:** VS Code  
**Glasses needed:** No  
**Goal:** Working `/analyze` endpoint that correctly identifies a Malacca landmark

| Step | Task | Success Check |
|---|---|---|
| 1.1 | Set up Python venv, install dependencies | `pip install` runs without error |
| 1.2 | Create `main.py` with FastAPI skeleton | `GET /health` returns `{"status":"ok"}` |
| 1.3 | Create `services/vision.py` with Gemini 2.5 Flash | Returns text for a test image |
| 1.4 | Add system prompt for Malacca tourist context | Response mentions correct landmark name |
| 1.5 | Create `services/search.py` for web search | Returns ticket prices when asked |
| 1.6 | Wire search into vision (Gemini tool use) | Single `/analyze` call handles vision + search |
| 1.7 | Create `models/schemas.py` Pydantic models | Request/response validated correctly |
| 1.8 | Test `/analyze` via FastAPI `/docs` UI | Upload Stadthuys photo → correct response |
| 1.9 | Add error handling + fallback messages | Bad image returns graceful error JSON |
| ✅ | **Phase 1 complete when:** | All 9 steps pass. Backend runs locally on port 8000. |

---

### Phase 2 — Android App with Phone Camera
**Tool:** Android Studio  
**Glasses needed:** No  
**Goal:** Full tourist flow working on phone — voice in, AI response, spoken back

#### 2A — Project Setup & API Connection
| Step | Task | Success Check |
|---|---|---|
| 2.1 | Create Android project (Kotlin, Compose, API 26) | App builds and runs on physical device |
| 2.2 | Add all dependencies to `build.gradle` | Gradle sync succeeds, no errors |
| 2.3 | Create `ApiClient.kt` + `ApiService.kt` | `GET /health` returns 200 from device on same WiFi |
| 2.4 | Create `ApiModels.kt` data classes | Request/response models match backend schema |

#### 2B — Navigation & Screens (UI Shell)
| Step | Task | Success Check |
|---|---|---|
| 2.5 | Set up `NavGraph.kt` with all 5 routes | Can navigate between screens manually |
| 2.6 | Build `SplashScreen.kt` | Logo shows, auto-navigates to Home after 2s |
| 2.7 | Build `HomeScreen.kt` shell | Mic button visible, BLE status shown at bottom |
| 2.8 | Build `ListeningScreen.kt` shell | Waveform animation plays |
| 2.9 | Build `LoadingScreen.kt` shell | Spinner + rotating messages animate correctly |
| 2.10 | Build `ResultScreen.kt` shell | Landmark name + response text + buttons render |
| 2.11 | Apply theme colours in `Color.kt` + `Theme.kt` | Dark theme, Malacca colours applied to all screens |

#### 2C — Camera + API Call
| Step | Task | Success Check |
|---|---|---|
| 2.12 | Create `CameraManager.kt` with CameraX | Tapping button takes a photo on the device |
| 2.13 | Wire camera photo → `POST /analyze` | Response JSON received in Android app |
| 2.14 | Display response on ResultScreen | Landmark name + AI text shows correctly |
| 2.15 | Show captured image thumbnail on ResultScreen | Image preview visible in LandmarkCard |

#### 2D — Voice Input (STT)
| Step | Task | Success Check |
|---|---|---|
| 2.16 | Create `SttManager.kt` with Android SpeechRecognizer | Voice transcribed to text on device |
| 2.17 | Wire STT result → query field in `/analyze` call | Spoken question sent as query to backend |
| 2.18 | Navigate Listening → Loading on speech end | Automatic transition when tourist stops speaking |
| 2.19 | Handle empty STT result gracefully | Toast shown, navigate back to Home |

#### 2E — Voice Output (TTS)
| Step | Task | Success Check |
|---|---|---|
| 2.20 | Create `TtsManager.kt` with Android TextToSpeech | Response text spoken aloud on arrival at ResultScreen |
| 2.21 | Wire "Replay" button to re-trigger TTS | Tapping Replay speaks response again |
| 2.22 | Stop TTS when navigating away from ResultScreen | No audio leak when going back to Home |

#### 2F — ViewModel & State
| Step | Task | Success Check |
|---|---|---|
| 2.23 | Create `MainViewModel.kt` with `AppUiState` sealed class | State transitions: Idle → Listening → Loading → Result |
| 2.24 | Wire all error states to `ErrorBanner` component | Each error shows correct message on correct screen |
| 2.25 | Add timeout handling (10s backend timeout) | App navigates back to Home on timeout |

#### 2H — Voice-Driven Food Recommendation (F06, F07)
| Step | Task | Success Check |
|---|---|---|
| 2.29 | Add `ACCESS_FINE_LOCATION` runtime permission request to Android | Device prompts user for location on first launch |
| 2.30 | Add GPS coordinate fetch to the ViewModel | `lat`/`lng` available before the restaurant call |
| 2.31 | Add `PlaceAlternative` + recommendation response to `ApiModels.kt` | Data classes match the `POST /restaurant` schema in §5.3 |
| 2.32 | Add `/restaurant` and `/restaurant/nearby` to `ApiService.kt` | Retrofit definitions compile without error |
| 2.33 | Add mode toggle (Landmark / Restaurant) to `HomeScreen.kt` | Toggling changes `appMode` state in ViewModel |
| 2.34 | Skip image capture in restaurant mode; go straight to listening | No photo is pulled; the flow reaches ListeningScreen in well under a second |
| 2.35 | Backend: Gemini interprets the recording into a structured search | Spoken "best cafe with pancakes" yields `{query, open_now, rank_by}` |
| 2.36 | Backend: Google Places `searchText` with GPS bias; rank and format | Real places returned with rating, review count, distance, hours |
| 2.37 | Reuse `NearbyPlaceCard` list UI for the results | Names, stars, distance, price and hours all render |
| 2.38 | Wire spoken refinement ("anything cheaper?") through the follow-up window | Second request narrows the previous results without touching the phone |
| 2.39 | Verify no place is ever invented | Every name in the spoken response has a `place_id` from Places |
| 2.40 | Test end-to-end on Jonker Street | Relevant, open, walkable suggestions spoken through the glasses |

#### 2G — Full End-to-End Test (Phone Camera)
| Step | Task | Success Check |
|---|---|---|
| 2.26 | Full flow test: voice → camera → AI → speak | Complete tourist experience works on phone |
| 2.27 | Test with 3 different Malacca landmark photos | All 3 identified correctly |
| 2.28 | Test all error states manually | Every error shows correct banner, no crashes |
| ✅ | **Phase 2 complete when:** | Tourist can use the full app with phone camera. Zero crashes. |

---

### Phase 3 — HeyCyan BLE Glasses Integration
**Tool:** Android Studio  
**Glasses needed:** Yes (physical HeyCyan glasses required)  
**Goal:** Replace phone camera with glasses camera. Everything else stays the same.

| Step | Task | Success Check |
|---|---|---|
| 3.1 | Copy `QCBleSdk.aar` into `app/libs/` | File exists in correct folder |
| 3.2 | Add AAR dependency in `build.gradle` | Project builds without AAR errors |
| 3.3 | Add all BLE permissions to `AndroidManifest.xml` | No permission-related build errors |
| 3.4 | Create `GlassesManager.kt` — BLE scan | Nearby glasses appear in a list |
| 3.5 | Implement connect + disconnect logic | App connects to glasses, HomeScreen shows "Glasses connected ✓" |
| 3.6 | Implement `QCSDKManagerDelegate` photo callback | Callback fires when glasses shutter pressed |
| 3.7 | Replace `CameraManager` with glasses photo bytes | Photo from glasses sent to `/analyze` |
| 3.8 | Test full flow with glasses | Tourist speaks → glasses capture → AI response spoken back |
| 3.9 | Test BLE drop recovery | Glasses disconnect → HomeScreen shows "Glasses disconnected" gracefully |
| ✅ | **Phase 3 complete when:** | Full flow works with glasses. App handles BLE drops without crashing. |

---

### Phase 4 — Database + Session History
**Tool:** VS Code + Android Studio  
**Glasses needed:** Optional  
**Goal:** Every interaction saved, tourist can review past discoveries

| Step | Task | Success Check |
|---|---|---|
| 4.1 | Set up Firebase project + Firestore + Cloud Storage | Firebase console shows project active |
| 4.2 | Add `services/database.py` — Firestore session save | Session document appears in Firestore console |
| 4.3 | Add image upload to Cloud Storage | Image URL stored in session document |
| 4.4 | Add `GET /sessions/{tourist_id}` endpoint | Returns correct session list as JSON |
| 4.5 | Add Firebase Auth (anonymous) to Android | Each install gets unique anonymous UID |
| 4.6 | Create `SessionEntity.kt` + `SessionDao.kt` (Room) | Sessions persist after app restart |
| 4.7 | Create `SessionRepository.kt` — syncs Firestore → Room | Local DB updated on app open |
| 4.8 | Wire session save into result flow | After ResultScreen loads, session auto-saved |
| 4.9 | Build `HistoryScreen.kt` with session list | Past sessions visible, scrollable |
| 4.10 | Tap session → ResultScreen (read-only) | Can review past landmarks |
| ✅ | **Phase 4 complete when:** | Sessions save correctly. HistoryScreen shows all past discoveries. |

---

### Phase 5 — Polish + Nice-to-Have
| Step | Feature | Notes |
|---|---|---|
| 5.1 | Upgrade STT to Gemini Live API (WebSocket) | Replace Android SpeechRecognizer — lower latency |
| 5.2 | Upgrade TTS to Gemini TTS API | Replace Android TextToSpeech — more natural voice |
| 5.3 | Multilingual response | Pass `language` param; detect from device locale |
| 5.4 | Offline landmark cache | Pre-load top 20 landmarks into Room DB |
| 5.5 | Map integration | Show Google Maps pin after landmark identified |
| 5.6 | Feedback (thumbs up/down) | Save rating to Firestore session document |
| 5.7 | Cloud Run deployment | Dockerize backend, deploy to GCP, update Android URL |

---

## 12. Key Rules for Claude Code Sessions

When starting a new Claude Code session, always begin with:

> "Read `prd.md` and `sdd.md` first.  
> We are on **Phase X, Step Y**.  
> Current task: [describe task]  
> Current error (if any): [paste full error message]"

**Never skip a phase's success checks before moving to the next phase.**  
**Never add glasses BLE code before Phase 2 is fully complete.**  
**Never deploy to Cloud Run before Phase 4 is complete.**
