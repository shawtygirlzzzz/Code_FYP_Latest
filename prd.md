# Product Requirements Document (PRD)
# HeyCyan Malacca Tourist Guide App

**Version:** 1.2  
**Date:** August 2026  
**Author:** [Your Name]  
**Status:** Draft

> **Revision note (v1.2).** Every feature now carries an implementation status, and
> §3.5 records which requirements were delivered, which were dropped, and why. F06
> and F07 are **not implemented** — Google Places requires paid billing that became
> unavailable mid-project, and the free alternative was evaluated and rejected.
>
> **Revision note (v1.1).** Restaurant mode was redesigned from photographing
> signage to asking for a recommendation by voice (F06, F07), and the voice input
> description was corrected to match what was built. See §3.4 for the rationale.

---

## 1. Overview

### 1.1 Product Summary
A mobile Android app that pairs with HeyCyan smart glasses to act as an AI-powered
tourist guide in Malacca (Melaka), Malaysia. Tourists wear the glasses, point the
camera at a landmark, ask a question by voice, and receive a spoken response — all
hands-free.

### 1.2 Problem Statement
Tourists in Malacca often encounter historical buildings, monuments, and cultural
sites without knowing their significance. Existing solutions (brochures, tour guides,
Google Maps) require stopping, looking at a screen, or hiring a human guide. There is
no seamless, hands-free way to get instant, accurate information about what you are
looking at.

### 1.3 Solution
Use HeyCyan smart glasses as the input device (camera + microphone + speaker) paired
with an Android app offering two hands-free modes:

**Landmark mode** — for something the tourist can see:
1. Captures an image of the landmark via the glasses camera
2. Records the tourist's voice question from the glasses microphone
3. Sends both to an AI backend for landmark identification and information retrieval
4. Speaks the response back through the glasses speaker

**Restaurant mode** — for something the tourist is looking *for*:
1. Records a spoken request such as "where is the best cafe that serves pancakes here?"
2. Sends the recording plus the tourist's GPS position to the backend — no photograph
3. Gemini interprets the request; Google Places supplies real nearby matches and ratings
4. Speaks the recommendations back through the glasses speaker

Both modes support spoken follow-up questions, so a session is a conversation rather
than a series of separate lookups.

---

## 2. Target Users

| User | Description |
|---|---|
| **Primary** | International and domestic tourists visiting Malacca |
| **Secondary** | Tour operators who want to offer tech-enhanced experiences |
| **Tertiary** | Students on educational field trips |

### 2.1 User Persona
**Name:** Amir, 34, tourist from Japan  
**Situation:** Visiting Malacca for 2 days, does not speak Malay or know local history  
**Goal:** Understand the buildings he passes without stopping to look at his phone  
**Pain point:** Google searches are slow, generic, and require screen interaction  

---

## 3. Features

### 3.1 Must Have (MVP — Phase 1, 2 & 3)

| # | Feature | Status | Description |
|---|---|---|---|
| F01 | Landmark identification | ✅ Implemented | Capture image via glasses, identify building using Gemini vision |
| F02 | Voice input | ✅ Implemented | Question recorded from the glasses microphone and sent to the backend as audio. Gemini interprets the recording directly, so no separate speech-to-text step exists |
| F03 | AI response | ✅ Implemented | LLM generates a tourist-friendly answer with historical information |
| F04 | Voice output (TTS) | ✅ Implemented | Answer spoken back through the glasses speaker |
| F05 | Web search grounding | ✅ Implemented (follow-ups only) | Search is enabled for follow-up questions, where current information such as ticket prices lives. It is deliberately **disabled for identification**, which is a vision task the search tool cannot assist with, and is billed per request |
| F06 | Voice-driven food recommendation | ❌ **Not implemented** | Specified in §3.4 and SDD §5.3 but never built. Google Places requires paid billing which expired mid-project; OpenStreetMap was evaluated as a free replacement and rejected (§3.5) |
| F07 | Refining recommendations | ❌ **Not implemented** | Depends on F06 |
| F08 | BLE glasses connection | ✅ Implemented | Scan, connect, device events, battery, and classic-Bluetooth audio bring-up |
| F09 | Session saving | ❌ **Not implemented** | Phase 4 was not reached. No Firestore, Room persistence or history screen |
| F16 | Hands-free follow-up | ✅ Implemented | After each successful answer the app plays a cue tone and listens briefly. Speaking continues the conversation; silence ends it. The glasses button always starts a new subject, so the two never conflict |

### 3.2 Nice to Have (Phase 4+)

| # | Feature | Description |
|---|---|---|
| F10 | Multilingual support | Respond in tourist's preferred language (EN, ZH, JA, AR) |
| F11 | History log screen | Tourist can review past interactions in the app |
| F12 | Offline mode | Cache top 20 Malacca landmarks for use without internet |
| F13 | Map integration | Show landmark location on map after identification |
| F14 | Audio tour mode | Pre-recorded walking tour triggered by GPS location |
| F15 | Feedback system | Tourist rates the response (thumbs up/down) to improve accuracy |

### 3.3 Out of Scope (v1.0)
- iOS version
- Web dashboard
- Social sharing
- Multi-user / group tour mode
- Identifying a specific restaurant by photographing its signage (see §3.4)

### 3.4 Design Change: Restaurant Mode (v1.0 → v1.1)

**v1.0 specified** photographing a restaurant's signage, reading the name from it with
Gemini, and looking that name up in Google Places.

**v1.1 replaces this** with a spoken request plus GPS, and no photograph.

Reasons for the change:

1. **It answers the question tourists actually ask.** Photographing a sign answers
   "what is this place in front of me?", which the tourist can largely see already.
   The real need — reflected in the persona and in §4 — is "where should I eat?",
   which the v1.0 design could not express at all.
2. **It removes the slowest step.** Pulling an image from the glasses over Bluetooth
   takes roughly six seconds. Restaurant mode needs no image, so it is faster than
   landmark mode rather than slower.
3. **It removes a failure mode.** Signage OCR fails on stylised logos, non-Latin
   scripts, poor angles and low light. A spoken preference has no such dependency.
4. **It requires the tourist to already be at the restaurant.** A recommendation
   feature is more useful before choosing than after arriving.

Ratings, review counts, prices and opening hours still come from Google Places, so
the factual content of the response is unchanged. What changes is how the candidate
places are chosen: by spoken preference rather than by photographed name.

### 3.5 Implementation Status and Dropped Requirements

**Delivered:** F01–F05, F08, F16. The complete hands-free loop works: the wearer
presses the button on the glasses, the photograph is pulled over Bluetooth, the
question is captured by the glasses microphone, and the answer is spoken through the
glasses speaker. Follow-up questions are spoken, not tapped.

**Dropped: F06 and F07 (restaurant / food recommendation).**

The feature depends on a place database with ratings. The chain of events was:

1. Google Places API (New) began returning `403 PERMISSION_DENIED`. Diagnosis showed
   this was **not** a quota problem but an authorisation one: the project's free trial
   had expired, and Maps Platform refuses all calls without active billing.
2. Paid billing was not available for this project.
3. **OpenStreetMap was evaluated** as a free replacement, using the Overpass API on
   the Jonker Street area (600m radius). Measured results:

   | | Coverage |
   |---|---|
   | Named places found | 79 |
   | Recording their cuisine | 27 of 79 (34%) |
   | Recording opening hours | 11 of 79 (14%) |
   | **Star ratings / review counts** | **none — OSM holds no rating data** |

   Query latency was 37 seconds on a mirror; the primary endpoint returned `504`.

4. Without ratings, "where is the *best* cafe" degrades to "here is the *nearest*
   cafe", and with only a third of places recording cuisine, filtering by a stated
   preference fails for most results. The feature could not be delivered honestly, so
   it was withdrawn rather than shipped in a misleading form.

The Places-based implementation remains in the repository (`services/places.py`,
`routers/restaurant.py`) as a record of the original design.

**Dropped: F09 (session saving).** Phase 4 was not reached. Effort was directed at
completing and validating the glasses integration instead, which is the component the
project's contribution rests on.

**Alternatives considered and rejected** during the search for a replacement feature,
recorded because the reasoning is itself a finding:

| Candidate | Why rejected |
|---|---|
| Sign / menu translation | Malacca's signage is already multilingual by law and convention. Street signs carry Malay, Jawi, Chinese, Tamil **and English**; menus are predominantly bilingual. A translator would restate what the sign already says |
| Dish identification | Technically feasible but a thin variant of F01 — image in, description out — with no distinct logic |
| AI trip planner | Does not use the camera at all, so nothing about it requires smart glasses |
| GPS-triggered audio tour | Viable, and remains the strongest future extension (F14), but GPS accuracy among the old town's shophouses (10–20m) cannot separate landmarks in Dutch Square without fusing camera confirmation |

---

## 4. User Stories

### Core Flow
```
As a tourist wearing HeyCyan glasses,
I want to look at a building and ask "What is this place?",
So that I can learn about it without stopping or looking at my phone.
```

### Supporting Stories
```
As a tourist, I want the response spoken aloud through the glasses,
so that I can keep walking while listening.

As a tourist, I want to ask follow-up questions like "How much is the ticket?",
so that I can plan my visit without searching the web manually.

As a tourist, I want my session history saved,
so that I can review what I learned at the end of the day.

As a tourist, I want the app to work even on slow mobile data,
so that I can use it throughout Malacca without WiFi.
```

### Restaurant & Cafe Flow
```
As a tourist walking on Jonker Street,
I want to ask "where is the best cafe that serves pancakes here?",
So that I can find somewhere to eat without stopping to search my phone.

As a tourist, I want to hear each place's rating, distance and opening hours,
So that I can make a quick decision while still walking.

As a tourist, I want to refine the suggestions by saying "anything cheaper?"
or "somewhere closer", so that I can narrow them down without touching anything.

As a tourist who does not know what I want yet, I want to ask something open
like "where should I eat around here?",
So that the app suggests options rather than requiring me to name a dish.
```

---

## 5. Key Landmarks (Initial Test Dataset)

| Landmark | Type |
|---|---|
| Stadthuys (Red Building) | Dutch colonial building |
| A Famosa (Porta de Santiago) | Portuguese fortress |
| St. Paul's Church | Historical ruins |
| Jonker Street (Jalan Hang Jebat) | Heritage street |
| Cheng Hoon Teng Temple | Oldest Chinese temple in Malaysia |
| Kampung Morten | Traditional Malay village |
| Malacca Sultanate Palace Museum | Cultural museum |
| Christ Church Melaka | Dutch Reformed church |
| The Shore Sky Tower | Modern landmark |
| Menara Taming Sari | Revolving tower |

---

## 6. Non-Functional Requirements

| Category | Requirement |
|---|---|
| **Latency** | Voice-to-response under 4 seconds on 4G connection, measured from the end of the question to the start of the spoken answer. Landmark mode additionally transfers a photograph over Bluetooth (~6s), which is measured separately; restaurant mode sends no image and should meet the 4s target end to end. |
| **Accuracy** | Correctly identifies top 20 Malacca landmarks ≥ 90% of the time |
| **Availability** | Backend uptime ≥ 99% (Cloud Run auto-scaling) |
| **Language** | Default response in English; multilingual in Phase 5 |
| **Privacy** | Images not stored permanently without user consent |
| **Offline** | App does not crash without internet; shows graceful error message |
| **Battery** | BLE connection should not drain phone battery more than 10%/hour |
| **Android** | Minimum Android API 26 (Android 8.0) |

---

## 7. Success Metrics (MVP)

| Metric | Target | Status |
|---|---|---|
| Landmark identification accuracy | ≥ 90% for top 20 landmarks | To be measured |
| Spoken question understood correctly | ≥ 90% of requests | To be measured |
| Backend response time (question sent → answer returned) | ≤ 4 seconds | **Median 4.1s measured** (see §7.1) |
| Photograph transfer over Bluetooth | measured separately | ~5–6 seconds |
| BLE connection stability | < 1 drop per 30-minute session | To be measured |
| Crash-free sessions | ≥ 95% | To be measured |
| Recommendation relevance | — | N/A — F06 not implemented |
| Session save success rate | — | N/A — F09 not implemented |

### 7.1 Measured: Model Selection and Response Time

Response time was initially 28–80 seconds, far outside target. Each suspected cause
was eliminated by measurement rather than assumption:

| Hypothesis | Test | Result |
|---|---|---|
| Search grounding is the cost | Disabled it | Still 28.6s — **not the cause** |
| Model "thinking" is the cost | `thinkingBudget=0` | Saved ~4s — not the cause |
| Long output is the cost | 94 output tokens took 25s | Not the cause |
| **Free-tier capacity** | Compared model variants on identical input | **Confirmed** |

Same photograph, same prompt, image and audio together:

| Model | Response time | Identified correctly |
|---|---|---|
| `gemini-2.5-flash` | ~28s (503 errors common) | ✓ |
| `gemini-3.5-flash` | 5.9s | ✓ |
| `gemini-3.5-flash-lite` | 5.1s | ✓ |

`gemini-3.5-flash-lite` was adopted, pinned rather than using a `-latest` alias so
results remain reproducible. Measured through the live endpoint afterwards: 4.1s
median across five runs (min 3.3s, max 9.9s), 5/5 successful.

Search grounding was retained for follow-up questions only — it was not the latency
cause, but it is billed per request and does not assist image recognition.

---

## 8. Assumptions & Risks

| | Description |
|---|---|
| **Assumption** | Tourist has Android phone (API 26+) and HeyCyan glasses |
| **Assumption** | Tourist has mobile data (4G minimum) while walking in Malacca |
| **Risk** | HeyCyan SDK BLE errors may delay glasses integration |
| **Mitigation** | Build and validate backend + app with phone camera first; add glasses last |
| **Risk** | Gemini free-tier models suffer capacity limits and rate limiting |
| **Mitigation** | Model choice made empirically (§7.1); `gemini-2.5-flash` was abandoned after measuring ~28s responses and frequent 503s. Rate-limit (429) responses are reported honestly rather than as an image problem |
| **Risk** | Paid APIs may become unavailable mid-project |
| **Realised** | Google Places trial billing expired, blocking F06/F07 entirely (§3.5). Gemini was unaffected as its free tier is independent of Cloud billing |
| **Risk** | Landmark recognition may fail at odd angles or at night |
| **Mitigation** | Add fallback prompt: "I could not identify this clearly, try a closer angle" |
| **Risk** | An LLM asked to name restaurants may invent places, or list ones long closed |
| **Mitigation** | Gemini only interprets the spoken request. Every name, rating, price and opening time comes from Google Places, so nothing reaches the tourist unverified |
| **Risk** | GPS accuracy degrades among the dense shophouses of the old town, giving wrong distances |
| **Mitigation** | Bias the search generously rather than filtering hard on radius; speak approximate distances |
| **Risk** | Gemini capacity limits cause very slow responses (a 71-second failure was observed in testing) |
| **Mitigation** | Time out the request and speak a clear message rather than leaving the tourist waiting in silence |
| **Risk** | A narrow request ("pancakes") may match nothing nearby |
| **Mitigation** | Widen the search and say so, rather than reporting no results |

---

## 9. Build Phases Summary

| Phase | Scope | Deliverable |
|---|---|---|
| **Phase 1** | Python FastAPI backend | `/analyze` endpoint working with Gemini Vision + web search |
| **Phase 2** | Android app (phone camera) | Full voice → Vision → TTS loop + voice-driven food recommendation (F06, F07) |
| **Phase 3** | HeyCyan BLE integration | Replace phone camera with glasses camera trigger |
| **Phase 4** | Database + session history | Firestore save, history screen, error handling |
| **Phase 5** | Polish + nice-to-have | Multilingual, offline cache, map, feedback rating |
