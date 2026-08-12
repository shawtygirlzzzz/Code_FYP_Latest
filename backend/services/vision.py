import os
from google import genai
from google.genai import types
from services.search import build_search_tool

SYSTEM_PROMPT = """You are HeyCyan, an expert AI tourist guide specialising in Malacca (Melaka), Malaysia.
You know Malacca's landmarks, museums, temples, mosques, streets and monuments well, and you
have Google Search available to confirm details.

When given an image:
1. Examine it carefully before naming anything: type of structure (gate/arch, church, temple,
   mosque, palace, museum, street, tower, ship), presence of cannons, statues, roofing, elevation,
   and architectural style (Portuguese, Dutch, British, Chinese, Malay, Peranakan, modern).
2. Identify the landmark. Use your own knowledge and web search — you are NOT limited to the
   disambiguation list below.
3. Give a concise, engaging tourist-friendly description (3-5 sentences).
4. Include historical significance, who built it, and approximate year if known.
5. Mention one interesting or surprising fact.

DISAMBIGUATION NOTES — these are NOT the only landmarks you may identify. They are tie-breakers
for Malacca sites that are commonly confused with each other. Consult them only when the image
resembles one of these; otherwise identify the landmark normally from your own knowledge:
- A Famosa (Porta de Santiago): A standalone stone GATEWAY/ARCH at GROUND LEVEL at the foot of St. Paul's Hill. Key features: cannons on both sides at the base, Portuguese coat of arms carved above the arch. It is a GATE, not a church building.
- St. Paul's Church (Gereja St. Paul): A roofless stone CHURCH BUILDING at the TOP of St. Paul's Hill. Key features: white statue of St. Francis Xavier standing in front, church walls with no roof, no cannons, sits at higher elevation than A Famosa.
- Stadthuys: Large red Dutch colonial building in Dutch Square, near the red clock tower.
- Christ Church Melaka: Red Dutch colonial church with a white cross, in Dutch Square adjacent to Stadthuys.
- Cheng Hoon Teng Temple: Chinese temple with ornate roof, colourful ceramic figurines, red lanterns.
- Jonker Street (Jalan Hang Jebat): Busy street lined with shophouses, souvenir stalls, and food vendors.
- Malacca Sultanate Palace (Istana Kesultanan Melaka): Traditional Malay wooden palace structure.
- Menara Taming Sari: Modern tall revolving gyro tower.

Other Malacca landmarks you may well be shown include, among many others: the Maritime Museum
(Muzium Samudera, the Flor de la Mar ship replica), Kampung Morten, Masjid Selat Melaka (the
floating mosque), Kampung Kling Mosque, Sri Poyyatha Vinayagar Moorthi Temple, the Melaka River
waterfront, Dutch Square and its clock tower, The Shore Sky Tower, Villa Sentosa, and the
Baba & Nyonya Heritage Museum. Never refuse to identify a landmark merely because it is absent
from the disambiguation notes.

OUTPUT FORMAT — always begin your reply with exactly these two lines, then a blank line,
then the spoken description:
LANDMARK: <the landmark's common English name, or UNKNOWN if you genuinely cannot tell>
CONFIDENCE: <high | medium | low>

Choose CONFIDENCE as follows:
- high: you are confident which specific landmark this is.
- medium: you have a probable identification but some doubt remains.
- low: you cannot tell, the image is too unclear, or you are guessing.

Rules:
- The description after the two header lines is read aloud by text-to-speech. Plain text only:
  no markdown, no asterisks, no bullet points, no headings.
- Never make up information. If unsure about a detail, omit it.
- If you truly cannot identify it, use LANDMARK: UNKNOWN with CONFIDENCE: low, and say so plainly
  in the description while still describing what you can see.
- Write the description entirely in the language specified. The two header lines stay in English.
"""

LANGUAGE_NAMES = {
    "en": "English",
    "ms": "Bahasa Melayu",
}

# Only a fallback now — the model states its own confidence on a header line.
# Kept deliberately loose ("cannot ... identify" is written many ways) because a
# missed refusal used to score as high confidence, which also suppressed the
# app's automatic full-resolution retry.
LOW_CONFIDENCE_PHRASES = [
    # English
    "couldn't clearly identify", "could not clearly identify",
    "cannot identify", "can't identify", "cannot confidently identify",
    "can't confidently identify", "unable to identify", "unable to confidently",
    "not sure", "unclear", "try a closer", "difficult to identify",
    "cannot determine", "can't determine",
    # Bahasa Melayu
    "tidak dapat mengenal pasti", "tidak dapat mengenalpasti", "tidak pasti",
    "tidak jelas", "cuba lebih dekat", "sukar untuk mengenal pasti",
]


def get_gemini_client() -> genai.Client:
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("GEMINI_API_KEY not set in environment")
    return genai.Client(api_key=api_key)


def analyze_landmark(
    image_bytes: bytes,
    query: str,
    language: str = "en",
    landmark_context: str = "",
    audio_bytes: bytes | None = None,
    audio_mime: str = "audio/wav",
) -> dict:
    """
    Send image + question to Gemini 2.5 Flash.

    The question arrives either as text or, when the app recorded it from the
    glasses microphone, as audio. Gemini reads the audio directly, which avoids
    a separate speech-to-text step — on-device recognition proved unreliable over
    the glasses' narrowband Bluetooth link.

    Returns dict with landmark_name, response, confidence.
    """
    client = get_gemini_client()

    lang_name = LANGUAGE_NAMES.get(language, "English")
    base_query = query if query else "What is this landmark? Tell me about it."
    if audio_bytes:
        base_query = (
            "The tourist's question is in the attached audio clip. Listen to it and "
            "answer it directly. If the audio is unclear or contains no question, "
            "simply describe the landmark in the image."
        )

    if landmark_context:
        user_prompt = (
            f"The tourist is currently at {landmark_context}. "
            f"They are asking a follow-up question. "
            f"Do NOT re-identify or re-describe the landmark. "
            f"Answer the tourist's specific question directly and concisely.\n\n"
            f"Tourist question: {base_query}\n\n"
            f"Still use the two header lines, with LANDMARK: {landmark_context}.\n"
            f"IMPORTANT: Write the description entirely in {lang_name}."
        )
    else:
        user_prompt = (
            f"Identify the landmark in this image. Pay close attention to distinguishing "
            f"features such as cannons, statues, whether it is a gate or a church, and its "
            f"elevation before naming it. Consult the disambiguation notes only if the image "
            f"resembles one of the sites listed there; otherwise rely on your own knowledge "
            f"of Malacca and web search.\n\n"
            f"Tourist question: {base_query}\n\n"
            f"IMPORTANT: Write the description entirely in {lang_name}."
        )

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        config=types.GenerateContentConfig(
            system_instruction=SYSTEM_PROMPT,
            temperature=0.4,
            tools=[build_search_tool()],
        ),
        contents=_build_contents(image_bytes, user_prompt, audio_bytes, audio_mime),
    )

    raw_text = response.text.strip().replace("**", "")
    landmark_name, confidence, spoken_text = _parse_response(raw_text)

    return {
        "landmark_name": landmark_name,
        "response": spoken_text,
        "confidence": confidence,
    }


def _build_contents(
    image_bytes: bytes,
    user_prompt: str,
    audio_bytes: bytes | None,
    audio_mime: str,
) -> list:
    parts = [types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg")]
    if audio_bytes:
        parts.append(types.Part.from_bytes(data=audio_bytes, mime_type=audio_mime))
    parts.append(types.Part.from_text(text=user_prompt))
    return parts


def _parse_response(raw_text: str) -> tuple[str | None, str, str]:
    """
    Split Gemini's reply into (landmark_name, confidence, spoken_text).

    The model is asked to state the name and its own confidence on two header
    lines. Previously both were guessed from the prose, which misfired badly:
    "I cannot confidently identify..." did not contain the literal phrase
    "cannot identify", so an outright refusal was scored as high confidence,
    and the name came from a hardcoded list of twelve landmarks so anything
    else fell back to a truncated first sentence.

    The header lines are stripped so text-to-speech never reads them aloud.
    Falls back to the old heuristics if the model omits the headers.
    """
    landmark_name: str | None = None
    confidence: str | None = None
    body_lines: list[str] = []

    for line in raw_text.splitlines():
        stripped = line.strip()
        lowered = stripped.lower()
        if landmark_name is None and lowered.startswith("landmark:"):
            value = stripped.split(":", 1)[1].strip()
            landmark_name = None if value.upper() in ("UNKNOWN", "") else value
            continue
        if confidence is None and lowered.startswith("confidence:"):
            value = stripped.split(":", 1)[1].strip().lower()
            confidence = value if value in ("high", "medium", "low") else None
            continue
        body_lines.append(line)

    spoken_text = "\n".join(body_lines).strip()
    if not spoken_text:
        spoken_text = raw_text

    # The model ignored the format — fall back to inspecting the prose.
    if confidence is None:
        confidence = _assess_confidence(spoken_text)
    if landmark_name is None and confidence != "low":
        landmark_name = _extract_landmark_name(spoken_text)

    return landmark_name, confidence, spoken_text


def _assess_confidence(response_text: str) -> str:
    text_lower = response_text.lower()
    if any(phrase in text_lower for phrase in LOW_CONFIDENCE_PHRASES):
        return "low"
    if len(response_text) < 100:
        return "medium"
    return "high"


def _extract_landmark_name(response_text: str) -> str:
    known_landmarks = [
        "Stadthuys",
        "A Famosa",
        "Porta de Santiago",
        "St. Paul's Church",
        "Jonker Street",
        "Jalan Hang Jebat",
        "Cheng Hoon Teng",
        "Kampung Morten",
        "Malacca Sultanate Palace",
        "Christ Church",
        "Shore Sky Tower",
        "Menara Taming Sari",
    ]
    for name in known_landmarks:
        if name.lower() in response_text.lower():
            return name
    # fallback: return first sentence as landmark hint
    first_sentence = response_text.split(".")[0].strip()
    return first_sentence[:60] if len(first_sentence) > 60 else first_sentence