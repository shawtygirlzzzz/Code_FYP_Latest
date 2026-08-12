import asyncio
import uuid
import logging
from fastapi import APIRouter, UploadFile, File, Form
from models.schemas import RestaurantResponse, NearbyRequest, NearbyResponse, PlaceAlternative
from services import places

logger = logging.getLogger(__name__)
router = APIRouter()

_PRICE_LABELS = {
    "en": {0: "Price not listed", 1: "Affordable", 2: "Moderately priced",
           3: "Pricey", 4: "Very expensive"},
    "ms": {0: "Harga tidak dinyatakan", 1: "Berpatutan", 2: "Harga sederhana",
           3: "Mahal", 4: "Sangat mahal"},
}


def _price_label(level: int, language: str = "en") -> str:
    labels = _PRICE_LABELS.get(language, _PRICE_LABELS["en"])
    return labels.get(level, labels[0])


def _spoken_main(d: dict, price_str: str, language: str) -> str:
    if language == "ms":
        return (
            f"Ini ialah {d['restaurant_name']}, dinilai {d['rating']} daripada 5 bintang "
            f"dengan {d['review_count']} ulasan. Ia menyajikan makanan {d['cuisine']}. "
            f"{price_str}. {d['opening_hours']}."
        )
    return (
        f"This is {d['restaurant_name']}, rated {d['rating']} out of 5 stars "
        f"with {d['review_count']} reviews. It serves {d['cuisine']} food. "
        f"{price_str}. {d['opening_hours']}."
    )


def _review_prefix(language: str) -> str:
    return {"ms": "Seorang pengulas berkata: "}.get(language, "One reviewer said: ")


def _nearby_intro(language: str) -> str:
    return {"ms": "Berikut adalah beberapa restoran berdekatan. "}.get(language, "Here are some nearby alternatives. ")


def _nearby_item(a, price_str: str, language: str) -> str:
    if language == "ms":
        return (f"{a.name}, dinilai {a.rating} bintang, {a.distance_m} meter dari sini, "
                f"menyajikan makanan {a.cuisine}. {price_str}. {a.opening_hours}.")
    return (f"{a.name}, rated {a.rating} stars, {a.distance_m} metres away, "
            f"serving {a.cuisine} food. {price_str}. {a.opening_hours}.")


@router.post("/restaurant", response_model=RestaurantResponse)
async def restaurant_identify(
    image: UploadFile = File(...),
    lat: float = Form(...),
    lng: float = Form(...),
    language: str = Form(default="en"),
):
    if image.content_type not in ("image/jpeg", "image/png", "image/jpg"):
        return RestaurantResponse(
            status="error",
            message="Only JPEG and PNG images are supported.",
        )

    image_bytes = await image.read()
    if len(image_bytes) > 5 * 1024 * 1024:
        return RestaurantResponse(
            status="error",
            message="Image must be under 5MB.",
        )

    try:
        name = await asyncio.to_thread(places.extract_restaurant_name, image_bytes)
    except Exception as e:
        logger.error("Name extraction failed: %s", e)
        return RestaurantResponse(
            status="error",
            message="Failed to process the image. Please try again.",
        )

    if not name or name.upper() == "UNKNOWN":
        return RestaurantResponse(
            status="error",
            message="Could not identify a restaurant from this image. Try a closer angle on the signage.",
        )

    try:
        details = await asyncio.to_thread(places.get_restaurant_details, name, lat, lng, language)
    except Exception as e:
        logger.error("Places API error: %s", e)
        return RestaurantResponse(
            status="error",
            message="Failed to fetch restaurant details. Please check your connection.",
        )

    if not details:
        return RestaurantResponse(
            status="error",
            message=f"Could not find '{name}' on Google Maps. Try a clearer shot of the signage.",
        )

    price_str = _price_label(details["price_level"], language)
    spoken = _spoken_main(details, price_str, language)
    if details["top_review"]:
        spoken += " " + _review_prefix(language) + details["top_review"]

    return RestaurantResponse(
        status="success",
        restaurant_name=details["restaurant_name"],
        place_id=details["place_id"],
        rating=details["rating"],
        review_count=details["review_count"],
        top_review=details["top_review"],
        price_level=details["price_level"],
        opening_hours=details["opening_hours"],
        cuisine=details["cuisine"],
        response=spoken,
        session_id=str(uuid.uuid4()),
    )


@router.post("/restaurant/nearby", response_model=NearbyResponse)
async def restaurant_nearby(body: NearbyRequest):
    try:
        raw = await asyncio.to_thread(places.get_nearby_restaurants, body.lat, body.lng, body.exclude_place_id, body.language)
    except Exception as e:
        logger.error("Nearby search failed: %s", e)
        return NearbyResponse(
            status="error",
            message="Failed to fetch nearby restaurants. Please check your connection.",
        )

    if not raw:
        return NearbyResponse(
            status="error",
            message="No open restaurants found nearby right now. Try again later or explore further.",
        )

    alternatives = [PlaceAlternative(**a) for a in raw]

    parts = []
    for a in alternatives:
        price_str = _price_label(a.price_level, body.language)
        parts.append(_nearby_item(a, price_str, body.language))
    spoken = _nearby_intro(body.language) + " ".join(parts)

    return NearbyResponse(
        status="success",
        alternatives=alternatives,
        response=spoken,
    )
