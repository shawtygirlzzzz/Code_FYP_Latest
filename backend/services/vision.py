import os
from google import genai
from google.genai import types
from services.search import build_search_tool

SYSTEM_PROMPT = """You are HeyCyan, an expert AI tourist guide specialising in Malacca (Melaka), Malaysia.

When given an image of a landmark:
1. Before naming the landmark, carefully examine: type of structure (gate/arch, church, palace, street, tower), presence of cannons, statues, roofing, elevation, and architectural style (Portuguese, Dutch, Chinese, Malay, modern).
2. Use the landmark reference guide below to make a confident, accurate identification.
3. Provide a concise, engaging tourist-friendly description (3-5 sentences) starting with the landmark name.
4. Include historical significance, who built it, and approximate year if known.
5. Mention one interesting or surprising fact.

LANDMARK REFERENCE GUIDE — use this to distinguish visually similar sites:
- A Famosa (Porta de Santiago): A standalone stone GATEWAY/ARCH at GROUND LEVEL at the foot of St. Paul's Hill. Key features: cannons on both sides at the base, Portuguese coat of arms carved above the arch. It is a GATE, not a church building.
- St. Paul's Church (Gereja St. Paul): A roofless stone CHURCH BUILDING at the TOP of St. Paul's Hill. Key features: white statue of St. Francis Xavier standing in front, church walls with no roof, no cannons, sits at higher elevation than A Famosa.
- Stadthuys: Large red Dutch colonial building in Dutch Square, near the red clock tower.
- Christ Church Melaka: Red Dutch colonial church with a white cross, in Dutch Square adjacent to Stadthuys.
- Cheng Hoon Teng Temple: Chinese temple with ornate roof, colourful ceramic figurines, red lanterns.
- Jonker Street (Jalan Hang Jebat): Busy street lined with shophouses, souvenir stalls, and food vendors.
- Malacca Sultanate Palace (Istana Kesultanan Melaka): Traditional Malay wooden palace structure.
- Menara Taming Sari: Modern tall revolving gyro tower.

Rules:
- Respond in plain text only. No markdown, no asterisks, no bullet points.
- Keep language clear and simple — the response will be read aloud via text-to-speech.
- Never make up information. If unsure about a detail, omit it.
- If you cannot confidently identify the landmark, say so clearly.
- Always respond entirely in the language specified.
"""

LANGUAGE_NAMES = {
    "en": "English",
    "ms": "Bahasa Melayu",
}

LOW_CONFIDENCE_PHRASES = [
    # English
    "couldn't clearly identify", "cannot identify", "not sure",
    "unclear", "try a closer", "unable to identify",
    # Bahasa Melayu
    "tidak dapat mengenal pasti", "tidak pasti", "tidak jelas", "cuba lebih dekat",
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
            f"IMPORTANT: Respond entirely in {lang_name}."
        )
    else:
        user_prompt = (
            f"Identify the landmark in this image using the landmark reference guide. "
            f"Pay close attention to distinguishing features such as cannons, statues, "
            f"whether it is a gate or a church, and its elevation before naming it.\n\n"
            f"Tourist question: {base_query}\n\n"
            f"IMPORTANT: Respond entirely in {lang_name}."
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

    response_text = response.text.strip().replace("**", "")

    confidence = _assess_confidence(response_text)
    landmark_name = _extract_landmark_name(response_text)

    return {
        "landmark_name": landmark_name,
        "response": response_text,
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