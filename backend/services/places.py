import os
import re
import math
import requests
from google import genai
from google.genai import types

PLACES_API_KEY = os.getenv("GOOGLE_PLACES_API_KEY")
PLACES_BASE = "https://places.googleapis.com/v1"

_PRICE_MAP = {
    "PRICE_LEVEL_FREE": 0,
    "PRICE_LEVEL_INEXPENSIVE": 1,
    "PRICE_LEVEL_MODERATE": 2,
    "PRICE_LEVEL_EXPENSIVE": 3,
    "PRICE_LEVEL_VERY_EXPENSIVE": 4,
}

_CUISINE_MAP = {
    "en": {
        "chinese_restaurant": "Chinese", "indian_restaurant": "Indian",
        "japanese_restaurant": "Japanese", "korean_restaurant": "Korean",
        "thai_restaurant": "Thai", "seafood_restaurant": "Seafood",
        "malay_restaurant": "Malay", "cafe": "Cafe",
        "bakery": "Bakery", "fast_food_restaurant": "Fast Food",
        "_default": "Local Food",
    },
    "ms": {
        "chinese_restaurant": "Cina", "indian_restaurant": "India",
        "japanese_restaurant": "Jepun", "korean_restaurant": "Korea",
        "thai_restaurant": "Thai", "seafood_restaurant": "Makanan Laut",
        "malay_restaurant": "Melayu", "cafe": "Kafe",
        "bakery": "Bakeri", "fast_food_restaurant": "Makanan Segera",
        "_default": "Makanan Tempatan",
    },
}

_OPEN_LABELS = {
    "en": {"open": "Open now", "closed": "Closed now", "unknown": "Hours not available"},
    "ms": {"open": "Sedang dibuka", "closed": "Tutup sekarang", "unknown": "Waktu tidak tersedia"},
}


def _get_gemini_client() -> genai.Client:
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise ValueError("GEMINI_API_KEY not set in environment")
    return genai.Client(api_key=api_key)


def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> int:
    R = 6371000
    p = math.pi / 180
    a = (math.sin((lat2 - lat1) * p / 2) ** 2 +
         math.cos(lat1 * p) * math.cos(lat2 * p) *
         math.sin((lng2 - lng1) * p / 2) ** 2)
    return int(2 * R * math.asin(math.sqrt(a)))


def _parse_opening_hours(hours_data: dict | None, language: str = "en") -> str:
    labels = _OPEN_LABELS.get(language, _OPEN_LABELS["en"])
    if not hours_data:
        return labels["unknown"]
    return labels["open"] if hours_data.get("openNow") else labels["closed"]


def _parse_cuisine(types_list: list, language: str = "en") -> str:
    cuisines = _CUISINE_MAP.get(language, _CUISINE_MAP["en"])
    for t in types_list:
        if t in cuisines:
            return cuisines[t]
    return cuisines["_default"]


_NEXT_SECTIONS = r"\b(Portion|Presentation|Price|Environment|Service|Ambiance|Atmosphere|Overall)\s+"


def _parse_price(raw_price: str) -> int:
    return _PRICE_MAP.get(raw_price, 0)


def _extract_food_section(text: str) -> str:
    food_match = re.search(r"\bFood\s+", text, re.IGNORECASE)
    if not food_match:
        return ""
    start = food_match.end()
    next_match = re.search(_NEXT_SECTIONS, text[start:], re.IGNORECASE)
    food_text = text[start: start + next_match.start()] if next_match else text[start:]
    return food_text.strip()


def _get_top_review(place_id: str) -> str:
    url = f"{PLACES_BASE}/places/{place_id}"
    headers = {
        "X-Goog-Api-Key": PLACES_API_KEY,
        "X-Goog-FieldMask": "reviews",
    }
    resp = requests.get(url, headers=headers, timeout=10)
    reviews = resp.json().get("reviews", [])
    if not reviews:
        return ""
    raw = reviews[0].get("text", {}).get("text", "")
    clean = raw.encode("ascii", errors="ignore").decode().replace("\n", " ").strip()
    food_section = _extract_food_section(clean)
    if food_section:
        return food_section
    if len(clean) <= 200:
        return clean
    snippet = clean[:200]
    last_end = max(snippet.rfind(". "), snippet.rfind("! "), snippet.rfind("? "))
    return snippet[:last_end + 1] if last_end > 30 else snippet


def extract_restaurant_name(image_bytes: bytes) -> str:
    client = _get_gemini_client()
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=[
            types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
            types.Part.from_text(
                text=(
                    "Extract the restaurant or cafe name from the signage in this image. "
                    "Return ONLY the name, nothing else. "
                    "If no clear restaurant or cafe name is visible, return UNKNOWN."
                )
            ),
        ],
    )
    return response.text.strip()


def get_restaurant_details(name: str, lat: float, lng: float, language: str = "en") -> dict | None:
    url = f"{PLACES_BASE}/places:searchText"
    headers = {
        "X-Goog-Api-Key": PLACES_API_KEY,
        "X-Goog-FieldMask": (
            "places.id,places.displayName,places.rating,"
            "places.userRatingCount,places.priceLevel,"
            "places.currentOpeningHours,places.types,places.location"
        ),
    }
    body = {
        "textQuery": name,
        "locationBias": {
            "circle": {
                "center": {"latitude": lat, "longitude": lng},
                "radius": 500.0,
            }
        },
        "maxResultCount": 1,
        "includedType": "restaurant",
    }
    resp = requests.post(url, json=body, headers=headers, timeout=10)
    data = resp.json()

    if "places" not in data or not data["places"]:
        return None

    p = data["places"][0]
    place_id = p.get("id", "")

    return {
        "place_id": place_id,
        "restaurant_name": p.get("displayName", {}).get("text", name),
        "rating": p.get("rating", 0.0),
        "review_count": p.get("userRatingCount", 0),
        "top_review": _get_top_review(place_id),
        "price_level": _parse_price(p.get("priceLevel", "")),
        "opening_hours": _parse_opening_hours(p.get("currentOpeningHours"), language),
        "cuisine": _parse_cuisine(p.get("types", []), language),
    }


def get_nearby_restaurants(lat: float, lng: float, exclude_place_id: str = None, language: str = "en") -> list:
    url = f"{PLACES_BASE}/places:searchNearby"
    headers = {
        "X-Goog-Api-Key": PLACES_API_KEY,
        "X-Goog-FieldMask": (
            "places.id,places.displayName,places.rating,"
            "places.priceLevel,places.currentOpeningHours,"
            "places.types,places.location"
        ),
    }
    body = {
        "includedTypes": ["restaurant", "cafe"],
        "maxResultCount": 20,
        "locationRestriction": {
            "circle": {
                "center": {"latitude": lat, "longitude": lng},
                "radius": 200.0,
            }
        },
    }
    resp = requests.post(url, json=body, headers=headers, timeout=10)
    raw = resp.json().get("places", [])

    if exclude_place_id:
        raw = [p for p in raw if p.get("id") != exclude_place_id]

    # keep only places that are currently open
    raw = [p for p in raw if p.get("currentOpeningHours", {}).get("openNow") is True]

    raw = sorted(raw, key=lambda x: x.get("rating", 0), reverse=True)[:3]

    result = []
    for p in raw:
        loc = p.get("location", {})
        dist = _haversine_m(lat, lng, loc.get("latitude", lat), loc.get("longitude", lng))
        result.append({
            "name": p.get("displayName", {}).get("text", "Unknown"),
            "rating": p.get("rating", 0.0),
            "price_level": _parse_price(p.get("priceLevel", "")),
            "distance_m": dist,
            "opening_hours": _parse_opening_hours(p.get("currentOpeningHours"), language),
            "cuisine": _parse_cuisine(p.get("types", []), language),
            "place_id": p.get("id", ""),
        })

    return result
