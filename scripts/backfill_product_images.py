"""Backfill missing smartphone image URLs from MobileAPI.dev safely.

Dry-run is the default. Pass ``--apply`` to write updates. The script reads only
``BACKFILL_DATABASE_URL`` and never falls back to the backend's database
configuration. Remote database writes additionally require
``--confirm-production``.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import sys
import time
import unicodedata
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, unquote, urlencode, urlsplit
from urllib.request import OpenerDirector, Request, build_opener


API_BASE = "https://api.mobileapi.dev"
API_PAGE_LIMIT = 50  # Current documented maximum (verified 11 November 2026).
USER_AGENT = "TechAdvisorImageBackfill/1.0"
BROWSER_IMAGE_ACCEPT = (
    "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
)
CACHE_VERSION = 2
DEFAULT_CACHE_DIR = Path(".backfill-cache/product-images-v2")
DEFAULT_CACHE_MAX_AGE_DAYS = 7

SELECT_MISSING_SMARTPHONES = """
SELECT id, brand, model_name, release_date
FROM products
WHERE category = 'SMARTPHONE'
  AND image_url IS NULL
ORDER BY id
"""

SELECT_SMARTPHONE_CATALOGUE = """
SELECT id, brand, model_name, release_date
FROM products
WHERE category = 'SMARTPHONE'
ORDER BY id
"""

SELECT_PRODUCT_FOR_UPDATE = """
SELECT id, brand, model_name, category, release_date, image_url
FROM products
WHERE id = %s
FOR UPDATE
"""

UPDATE_PRODUCT_IMAGE = """
UPDATE products
SET image_url = %s
WHERE id = %s
  AND category = 'SMARTPHONE'
  AND image_url IS NULL
  AND brand = %s
  AND model_name = %s
  AND release_date IS NOT DISTINCT FROM %s
"""

_SENSITIVE_QUERY_KEYS = {"key", "api_key", "apikey", "authorization", "token"}


class BackfillError(RuntimeError):
    """A safe-to-display operational error."""


class MobileApiError(BackfillError):
    """MobileAPI returned an unusable response or exhausted retries."""


class CacheValidationError(BackfillError):
    """The cache is corrupt, stale, or belongs to another catalogue."""


class ApiBudgetExhausted(MobileApiError):
    """The configured authenticated request budget has been consumed."""


@dataclass(frozen=True)
class Product:
    id: int
    brand: str
    model_name: str
    release_date: date | None = None


@dataclass(frozen=True)
class ProviderDevice:
    provider_id: str
    brand: str
    model_name: str
    release_date: date | None
    image_url: str | None


@dataclass(frozen=True)
class PlannedUpdate:
    product: Product
    image_url: str


@dataclass(frozen=True)
class MatchResult:
    status: str
    device: ProviderDevice | None = None


@dataclass
class Summary:
    total: int = 0
    matched: int = 0
    verified: int = 0
    would_update: int = 0
    updated: int = 0
    skipped: int = 0
    ambiguous: int = 0
    failed: int = 0
    api_requests: int = 0
    cached_pages_reused: int = 0
    cached_verifications_reused: int = 0
    cached_matches_reused: int = 0
    manufacturers_total: int = 0
    manufacturers_completed: int = 0
    manufacturers_remaining: int = 0
    complete: bool = True


@dataclass(frozen=True)
class DatabaseTarget:
    host: str
    is_remote: bool


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat()


def date_text(value: date | None) -> str | None:
    return value.isoformat() if value else None


def product_dict(product: Product) -> dict[str, Any]:
    return {
        "id": product.id,
        "brand": product.brand,
        "model_name": product.model_name,
        "release_date": date_text(product.release_date),
    }


def provider_dict(device: ProviderDevice) -> dict[str, Any]:
    return {
        "provider_id": device.provider_id,
        "brand": device.brand,
        "model_name": device.model_name,
        "release_date": date_text(device.release_date),
        "image_url": device.image_url,
    }


def provider_from_dict(value: Any) -> ProviderDevice:
    if not isinstance(value, dict):
        raise CacheValidationError("Cached provider match is invalid")
    required = ("provider_id", "brand", "model_name")
    if any(not isinstance(value.get(key), str) for key in required):
        raise CacheValidationError("Cached provider match is incomplete")
    image_url = value.get("image_url")
    if image_url is not None and not isinstance(image_url, str):
        raise CacheValidationError("Cached provider image URL is invalid")
    return ProviderDevice(
        provider_id=value["provider_id"],
        brand=value["brand"],
        model_name=value["model_name"],
        release_date=parse_release_date(value.get("release_date")),
        image_url=image_url,
    )


def _hash_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, indent=2, sort_keys=True, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _read_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CacheValidationError(f"{label} is corrupt; use --refresh-cache") from exc


class PersistentCache:
    """Atomic small-file cache for API pages, image checks, and matches."""

    def __init__(self, root: Path):
        self.root = root
        self.manifest_path = root / "manifest.json"
        self.manifest: dict[str, Any] | None = None

    def archive_for_refresh(self) -> Path | None:
        if not self.root.exists():
            return None
        resolved = self.root.resolve()
        if resolved == Path.cwd().resolve() or resolved.parent == resolved:
            raise CacheValidationError("Refusing to refresh an unsafe cache directory")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup = self.root.with_name(f"{self.root.name}.stale-{stamp}")
        suffix = 1
        while backup.exists():
            backup = self.root.with_name(f"{self.root.name}.stale-{stamp}-{suffix}")
            suffix += 1
        backup.parent.mkdir(parents=True, exist_ok=True)
        os.replace(self.root, backup)
        return backup

    def bind(self, database_identity: str, catalogue_fingerprint: str) -> None:
        if self.manifest_path.exists():
            manifest = _read_json(self.manifest_path, "Cache manifest")
            self._validate_manifest(manifest)
            if manifest["database_identity"] != database_identity:
                raise CacheValidationError(
                    "Cache belongs to a different database target; use --refresh-cache"
                )
            if manifest["catalogue_fingerprint"] != catalogue_fingerprint:
                raise CacheValidationError(
                    "Smartphone catalogue changed since caching; use --refresh-cache"
                )
            manifest.setdefault("unavailable_manufacturers", {})
            self.manifest = manifest
            return
        created = now_utc()
        self.manifest = {
            "version": CACHE_VERSION,
            "database_identity": database_identity,
            "catalogue_fingerprint": catalogue_fingerprint,
            "created_at": created,
            "updated_at": created,
            "authenticated_requests_total": 0,
            "processed_manufacturers": [],
            "unavailable_manufacturers": {},
            "last_run": None,
        }
        self._save_manifest()

    def load_for_inspection(self) -> dict[str, Any]:
        if not self.manifest_path.exists():
            raise CacheValidationError(f"No cache exists at {self.root}")
        manifest = _read_json(self.manifest_path, "Cache manifest")
        self._validate_manifest(manifest)
        manifest.setdefault("unavailable_manufacturers", {})
        self.manifest = manifest
        self.validate_files()
        return manifest

    def ensure_fresh(self, max_age_days: int) -> None:
        manifest = self._manifest()
        try:
            created = datetime.fromisoformat(manifest["created_at"])
        except (TypeError, ValueError) as exc:
            raise CacheValidationError("Cache timestamp is invalid; use --refresh-cache") from exc
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        if datetime.now(timezone.utc) - created > timedelta(days=max_age_days):
            raise CacheValidationError(
                f"Cache is older than {max_age_days} days; use --refresh-cache"
            )

    def record_authenticated_request(self, _run_count: int) -> None:
        manifest = self._manifest()
        manifest["authenticated_requests_total"] += 1
        self._save_manifest()

    def manufacturer(self, brand_key: str, display_name: str) -> dict[str, Any]:
        path = self._manufacturer_path(brand_key)
        if not path.exists():
            return {
                "brand_key": brand_key,
                "display_name": display_name,
                "pages": {},
                "listing_complete": False,
                "updated_at": now_utc(),
            }
        value = _read_json(path, f"Manufacturer cache for {display_name}")
        if (
            not isinstance(value, dict)
            or value.get("brand_key") != brand_key
            or not isinstance(value.get("pages"), dict)
        ):
            raise CacheValidationError(
                f"Manufacturer cache for {display_name} is invalid; use --refresh-cache"
            )
        return value

    def save_manufacturer_page(
        self,
        brand_key: str,
        display_name: str,
        page: int,
        payload: dict[str, Any],
    ) -> None:
        value = self.manufacturer(brand_key, display_name)
        value["pages"][str(page)] = {"fetched_at": now_utc(), "payload": payload}
        value["updated_at"] = now_utc()
        _atomic_json(self._manufacturer_path(brand_key), value)

    def mark_listing_complete(self, brand_key: str, display_name: str) -> None:
        value = self.manufacturer(brand_key, display_name)
        value["listing_complete"] = True
        value.pop("last_error", None)
        value["updated_at"] = now_utc()
        _atomic_json(self._manufacturer_path(brand_key), value)

    def record_manufacturer_failure(
        self, brand_key: str, display_name: str, reason: str
    ) -> None:
        value = self.manufacturer(brand_key, display_name)
        value["last_error"] = {"reason": reason, "recorded_at": now_utc()}
        value["updated_at"] = now_utc()
        _atomic_json(self._manufacturer_path(brand_key), value)

    def acknowledge_unavailable_manufacturer(
        self,
        brand_key: str,
        display_name: str,
        product_count: int,
    ) -> None:
        manifest = self._manifest()
        unavailable = manifest.setdefault("unavailable_manufacturers", {})
        unavailable[brand_key] = {
            "display_name": display_name,
            "product_count": product_count,
            "reason": "operator-confirmed provider manufacturer unavailable",
            "acknowledged_at": now_utc(),
        }
        processed = set(manifest["processed_manufacturers"])
        processed.add(brand_key)
        manifest["processed_manufacturers"] = sorted(processed)
        self._save_manifest()

    def mark_manufacturer_processed(self, brand_key: str) -> None:
        manifest = self._manifest()
        processed = set(manifest["processed_manufacturers"])
        processed.add(brand_key)
        manifest["processed_manufacturers"] = sorted(processed)
        self._save_manifest()

    def save_verification(self, url: str) -> dict[str, Any]:
        value = {"url": url, "verified": True, "verified_at": now_utc()}
        _atomic_json(self._verification_path(url), value)
        return value

    def verification(self, url: str) -> dict[str, Any] | None:
        path = self._verification_path(url)
        if not path.exists():
            return None
        value = _read_json(path, "Image verification cache")
        if (
            not isinstance(value, dict)
            or value.get("url") != url
            or value.get("verified") is not True
            or not isinstance(value.get("verified_at"), str)
        ):
            raise CacheValidationError("Image verification cache is invalid")
        return value

    def save_match(
        self,
        product: Product,
        status: str,
        *,
        provider: ProviderDevice | None = None,
        image_url: str | None = None,
        verified_at: str | None = None,
    ) -> None:
        value = {
            "product": product_dict(product),
            "status": status,
            "provider": provider_dict(provider) if provider else None,
            "image_url": image_url,
            "verified_at": verified_at,
            "updated_at": now_utc(),
        }
        _atomic_json(self._match_path(product.id), value)

    def match(self, product_id: int) -> dict[str, Any] | None:
        path = self._match_path(product_id)
        if not path.exists():
            return None
        value = _read_json(path, f"Product match cache for {product_id}")
        if not isinstance(value, dict) or not isinstance(value.get("product"), dict):
            raise CacheValidationError(f"Product match cache for {product_id} is invalid")
        return value

    def set_last_run(self, manufacturers: Sequence[str], complete: bool) -> None:
        manifest = self._manifest()
        manifest["last_run"] = {
            "manufacturers": sorted(manufacturers),
            "complete": complete,
            "finished_at": now_utc(),
        }
        self._save_manifest()

    def stats(self) -> dict[str, int]:
        pages = 0
        completed_listings = 0
        for path in sorted((self.root / "manufacturers").glob("*.json")):
            value = _read_json(path, f"Manufacturer cache {path.name}")
            if not isinstance(value, dict) or not isinstance(value.get("pages"), dict):
                raise CacheValidationError(f"Manufacturer cache {path.name} is invalid")
            pages += len(value["pages"])
            completed_listings += int(value.get("listing_complete") is True)
        return {
            "manufacturer_listings": completed_listings,
            "pages": pages,
            "verifications": len(list((self.root / "verifications").glob("*.json"))),
            "matches": len(list((self.root / "matches").glob("*.json"))),
        }

    def validate_files(self) -> None:
        self.stats()
        unavailable = self._manifest().get("unavailable_manufacturers", {})
        if not isinstance(unavailable, dict):
            raise CacheValidationError("Unavailable-manufacturer cache is invalid")
        for brand_key in self._manifest()["processed_manufacturers"]:
            if brand_key in unavailable:
                entry = unavailable[brand_key]
                if (
                    not isinstance(entry, dict)
                    or not isinstance(entry.get("display_name"), str)
                    or not isinstance(entry.get("product_count"), int)
                ):
                    raise CacheValidationError(
                        f"Unavailable manufacturer cache {brand_key} is invalid"
                    )
                continue
            path = self._manufacturer_path(brand_key)
            if not path.exists():
                raise CacheValidationError(
                    f"Completed manufacturer cache {brand_key} is missing"
                )
            value = _read_json(path, f"Manufacturer cache for {brand_key}")
            if (
                not isinstance(value, dict)
                or value.get("brand_key") != brand_key
                or value.get("listing_complete") is not True
            ):
                raise CacheValidationError(
                    f"Completed manufacturer cache {brand_key} is invalid"
                )
        for path in sorted((self.root / "verifications").glob("*.json")):
            value = _read_json(path, f"Verification cache {path.name}")
            if not isinstance(value, dict) or value.get("verified") is not True:
                raise CacheValidationError(f"Verification cache {path.name} is invalid")
        for path in sorted((self.root / "matches").glob("*.json")):
            value = _read_json(path, f"Match cache {path.name}")
            if not isinstance(value, dict) or not isinstance(value.get("product"), dict):
                raise CacheValidationError(f"Match cache {path.name} is invalid")

    def _save_manifest(self) -> None:
        manifest = self._manifest()
        manifest["updated_at"] = now_utc()
        _atomic_json(self.manifest_path, manifest)

    def _manifest(self) -> dict[str, Any]:
        if self.manifest is None:
            raise CacheValidationError("Cache is not bound to a database")
        return self.manifest

    @staticmethod
    def _validate_manifest(value: Any) -> None:
        if not isinstance(value, dict) or value.get("version") != CACHE_VERSION:
            raise CacheValidationError("Cache version is unsupported; use --refresh-cache")
        required = (
            "database_identity",
            "catalogue_fingerprint",
            "created_at",
            "authenticated_requests_total",
            "processed_manufacturers",
        )
        if any(key not in value for key in required):
            raise CacheValidationError("Cache manifest is incomplete; use --refresh-cache")
        if not isinstance(value["processed_manufacturers"], list):
            raise CacheValidationError("Cache manifest is invalid; use --refresh-cache")
        unavailable = value.get("unavailable_manufacturers", {})
        if not isinstance(unavailable, dict):
            raise CacheValidationError("Cache manifest is invalid; use --refresh-cache")

    def _manufacturer_path(self, brand_key: str) -> Path:
        return self.root / "manufacturers" / f"{_hash_text(brand_key)}.json"

    def _verification_path(self, url: str) -> Path:
        return self.root / "verifications" / f"{_hash_text(url)}.json"

    def _match_path(self, product_id: int) -> Path:
        return self.root / "matches" / f"{product_id}.json"


def normalize_name(value: str | None) -> str:
    """Return a deliberately conservative alphanumeric comparison key."""
    if not value:
        return ""
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return " ".join(re.findall(r"[a-z0-9]+", without_marks))


def normalize_model(value: str | None, brand: str | None) -> str:
    model = normalize_name(value)
    brand_key = normalize_name(brand)
    if brand_key and (model == brand_key or model.startswith(brand_key + " ")):
        model = model[len(brand_key) :].strip()
    return model


def parse_release_date(value: Any) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if not isinstance(value, str) or not value.strip():
        return None

    text = value.strip()
    iso = re.search(r"\b(\d{4})-(\d{2})-(\d{2})\b", text)
    if iso:
        try:
            return date(*(int(part) for part in iso.groups()))
        except ValueError:
            return None

    verbose = re.search(
        r"\b(\d{4})\s*,?\s+([A-Za-z]+)(?:\s+(\d{1,2}))?\b", text
    )
    if verbose:
        year, month_name, day = verbose.groups()
        for fmt in ("%B", "%b"):
            try:
                month = datetime.strptime(month_name, fmt).month
                return date(int(year), month, int(day or 1))
            except ValueError:
                continue
    return None


def _provider_brand(row: dict[str, Any], fallback: str) -> str:
    for key in ("manufacturer_name", "brand_name"):
        if isinstance(row.get(key), str) and row[key].strip():
            return row[key].strip()
    for key in ("manufacturer", "brand"):
        value = row.get(key)
        if isinstance(value, dict) and isinstance(value.get("name"), str):
            return value["name"].strip()
        if isinstance(value, str) and value.strip():
            return value.strip()
    return fallback


def _provider_image_url(row: dict[str, Any]) -> str | None:
    for key in ("image_url", "thumbnail_url", "thumb_url"):
        value = row.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    image = row.get("image")
    if isinstance(image, dict):
        value = image.get("url") or image.get("image_url")
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def provider_device(row: Any, fallback_brand: str) -> ProviderDevice | None:
    if not isinstance(row, dict):
        return None
    brand = _provider_brand(row, fallback_brand)
    model = row.get("name") or row.get("model_name")
    if not isinstance(model, str) or not normalize_name(brand) or not normalize_name(model):
        return None
    provider_id = str(row.get("id") or "").strip()
    return ProviderDevice(
        provider_id=provider_id,
        brand=brand,
        model_name=model.strip(),
        release_date=parse_release_date(row.get("release_date")),
        image_url=_provider_image_url(row),
    )


def match_product(product: Product, candidates: Iterable[ProviderDevice]) -> MatchResult:
    brand_key = normalize_name(product.brand)
    model_key = normalize_model(product.model_name, product.brand)
    exact = [
        device
        for device in candidates
        if normalize_name(device.brand) == brand_key
        and normalize_model(device.model_name, device.brand) == model_key
    ]
    if not exact:
        return MatchResult("unmatched")
    if len(exact) == 1:
        device = exact[0]
        if (
            product.release_date
            and device.release_date
            and product.release_date.year != device.release_date.year
        ):
            return MatchResult("unmatched")
        return MatchResult("matched", device)

    if product.release_date:
        same_day = [d for d in exact if d.release_date == product.release_date]
        if len(same_day) == 1:
            return MatchResult("matched", same_day[0])
        same_year = [
            d
            for d in exact
            if d.release_date and d.release_date.year == product.release_date.year
        ]
        if len(same_year) == 1:
            return MatchResult("matched", same_year[0])
    return MatchResult("ambiguous")


def valid_public_https_url(value: str | None) -> bool:
    if not value:
        return False
    try:
        parsed = urlsplit(value)
        if parsed.scheme.lower() != "https" or not parsed.hostname:
            return False
        if parsed.username or parsed.password:
            return False
        host = parsed.hostname.casefold().rstrip(".")
        if host == "localhost" or host.endswith((".localhost", ".local")):
            return False
        try:
            if not ipaddress.ip_address(host).is_global:
                return False
        except ValueError:
            pass
        if any(key.casefold() in _SENSITIVE_QUERY_KEYS for key, _ in parse_qsl(parsed.query)):
            return False
        parsed.port  # Force validation of an invalid textual port.
        return True
    except ValueError:
        return False


class PublicImageVerifier:
    """Anonymous browser-shaped image check with bounded transient retries."""

    def __init__(
        self,
        opener: OpenerDirector | Any | None = None,
        timeout: float = 10.0,
        max_retries: int = 2,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.opener = opener or build_opener()
        self.timeout = timeout
        self.max_retries = max(0, max_retries)
        self.sleep = sleep

    def verify(self, url: str) -> tuple[bool, str]:
        if not valid_public_https_url(url):
            return False, "invalid HTTPS image URL"
        result = (False, "image request failed")
        for attempt in range(self.max_retries + 1):
            result = self._request(url)
            if result[0] or not _transient_image_failure(result[1]):
                return result
            if attempt < self.max_retries:
                self.sleep(float(2**attempt))
        return result

    def _request(self, url: str) -> tuple[bool, str]:
        # DeviceThumb renders a plain <img src=... referrerPolicy="no-referrer">.
        # HEAD and Range are intentionally absent: neither is sent by that browser
        # flow, and some image origins answer those probe shapes with HTTP 406 even
        # though an ordinary GET is publicly displayable.
        request = Request(
            url,
            headers={"Accept": BROWSER_IMAGE_ACCEPT, "User-Agent": USER_AGENT},
            method="GET",
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                final_url = response.geturl()
                content_type = response.headers.get_content_type()
                if not valid_public_https_url(final_url):
                    return False, "redirected to an invalid URL"
                if not content_type.startswith("image/"):
                    return False, f"unexpected content type {content_type}"
                return True, "public image"
        except HTTPError as exc:
            return False, f"HTTP {exc.code}"
        except (URLError, TimeoutError, OSError):
            return False, "image request failed"


def _transient_image_failure(reason: str) -> bool:
    if reason == "image request failed":
        return True
    match = re.fullmatch(r"HTTP (\d{3})", reason)
    return bool(match and (match.group(1) == "429" or match.group(1).startswith("5")))


class MobileApiClient:
    """Bounded, rate-aware client for paginated manufacturer listings."""

    def __init__(
        self,
        api_key: str,
        *,
        opener: OpenerDirector | Any | None = None,
        timeout: float = 20.0,
        request_delay: float = 1.0,
        max_retries: int = 2,
        max_requests: int = 1000,
        sleep: Callable[[float], None] = time.sleep,
        request_observer: Callable[[int], None] | None = None,
        offline_cache_only: bool = False,
    ):
        if not api_key.strip() and not offline_cache_only:
            raise BackfillError("MOBILEAPI_API_KEY is required for a dry run")
        self._api_key = api_key.strip()
        self.opener = opener or build_opener()
        self.timeout = timeout
        self.request_delay = max(0.0, request_delay)
        self.max_retries = max(0, max_retries)
        self.max_requests = max_requests
        self.sleep = sleep
        self.request_observer = request_observer
        self.offline_cache_only = offline_cache_only
        self.request_count = 0
        self._rate_limit_delay = 0.0

    def fetch_manufacturer(
        self,
        manufacturer: str,
        *,
        cached_pages: dict[str, Any] | None = None,
        save_page: Callable[[int, dict[str, Any]], None] | None = None,
        progress: Callable[[str, int, int | None, bool], None] | None = None,
    ) -> tuple[list[ProviderDevice], int]:
        devices: list[ProviderDevice] = []
        seen: set[tuple[str, str, str, date | None, str | None]] = set()
        page = 1
        seen_pages: set[tuple[str, ...]] = set()
        cached_pages = cached_pages or {}
        cached_used = 0

        while True:
            cached = cached_pages.get(str(page))
            if cached is not None:
                if not isinstance(cached, dict) or not isinstance(cached.get("payload"), dict):
                    raise CacheValidationError(
                        f"Cached page {page} for {manufacturer} is invalid"
                    )
                payload = cached["payload"]
                from_cache = True
                cached_used += 1
            else:
                payload = self._get_json(
                    "/devices/by-manufacturer/",
                    {"manufacturer": manufacturer, "page": page, "limit": API_PAGE_LIMIT},
                )
                from_cache = False
                if save_page:
                    save_page(page, payload)
            if not isinstance(payload, dict) or not isinstance(payload.get("devices"), list):
                raise MobileApiError(
                    f"MobileAPI returned an invalid device page for {manufacturer}"
                )

            raw_devices = payload["devices"]
            total_pages = _positive_int(payload.get("total_pages"))
            if progress:
                progress(manufacturer, page, total_pages, from_cache)

            fingerprint = tuple(str(row.get("id")) for row in raw_devices if isinstance(row, dict))
            if fingerprint and fingerprint in seen_pages:
                raise MobileApiError(
                    f"MobileAPI repeated a page while listing {manufacturer}"
                )
            seen_pages.add(fingerprint)

            for row in raw_devices:
                parsed = provider_device(row, manufacturer)
                if not parsed:
                    continue
                key = (
                    parsed.provider_id,
                    normalize_name(parsed.brand),
                    normalize_model(parsed.model_name, parsed.brand),
                    parsed.release_date,
                    parsed.image_url,
                )
                if key not in seen:
                    seen.add(key)
                    devices.append(parsed)

            has_next = payload.get("has_next")
            if total_pages is not None:
                more = page < total_pages
            elif isinstance(has_next, bool):
                more = has_next
            else:
                more = len(raw_devices) >= API_PAGE_LIMIT
            if not more or not raw_devices:
                return devices, cached_used
            page += 1

    def _get_json(self, path: str, params: dict[str, Any]) -> Any:
        if self.offline_cache_only:
            raise MobileApiError(
                "offline cache-only mode encountered an uncached MobileAPI page"
            )
        query = urlencode(params)
        url = f"{API_BASE}{path}?{query}"
        last_error = "request failed"
        for attempt in range(self.max_retries + 1):
            if self.request_count >= self.max_requests:
                raise ApiBudgetExhausted(
                    f"MobileAPI request budget of {self.max_requests} was exhausted"
                )
            if self.request_count:
                self.sleep(max(self.request_delay, self._rate_limit_delay))
                self._rate_limit_delay = 0.0
            self.request_count += 1
            if self.request_observer:
                self.request_observer(self.request_count)
            request = Request(
                url,
                headers={
                    "Accept": "application/json",
                    "Authorization": f"Token {self._api_key}",
                    "User-Agent": USER_AGENT,
                },
            )
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    self._rate_limit_delay = _rate_limit_reset_delay(response.headers)
                    payload = json.loads(response.read().decode("utf-8"))
                    if not isinstance(payload, dict):
                        raise json.JSONDecodeError("Expected object", "", 0)
                    return payload
            except HTTPError as exc:
                last_error = f"HTTP {exc.code}"
                retryable = exc.code == 429 or 500 <= exc.code < 600
                if not retryable or attempt >= self.max_retries:
                    break
                self.sleep(_retry_delay(exc, attempt))
            except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError):
                last_error = "transport or JSON failure"
                if attempt >= self.max_retries:
                    break
                self.sleep(float(2**attempt))
        raise MobileApiError(f"MobileAPI {last_error} after retries")


def _positive_int(value: Any) -> int | None:
    try:
        number = int(value)
        return number if number >= 1 else None
    except (TypeError, ValueError):
        return None


def _retry_delay(error: HTTPError, attempt: int) -> float:
    retry_after = error.headers.get("Retry-After") if error.headers else None
    try:
        return min(300.0, max(0.0, float(retry_after)))
    except (TypeError, ValueError):
        reset_delay = _rate_limit_reset_delay(error.headers, require_exhausted=False)
        return reset_delay or float(max(1, 2**attempt))


def _rate_limit_reset_delay(headers: Any, *, require_exhausted: bool = True) -> float:
    if not headers:
        return 0.0
    if require_exhausted:
        try:
            if int(headers.get("X-RateLimit-Remaining", "1")) > 0:
                return 0.0
        except (TypeError, ValueError):
            return 0.0
    try:
        reset_at = float(headers.get("X-RateLimit-Reset"))
        return min(300.0, max(0.0, reset_at - time.time() + 1.0))
    except (TypeError, ValueError):
        return 0.0


def database_target(dsn: str) -> DatabaseTarget:
    try:
        parsed = urlsplit(dsn)
        host = parsed.hostname
    except ValueError as exc:
        raise BackfillError("BACKFILL_DATABASE_URL is not a valid PostgreSQL URL") from exc
    if parsed.scheme not in {"postgres", "postgresql"} or not host:
        raise BackfillError(
            "BACKFILL_DATABASE_URL must be an explicit postgres:// or postgresql:// URL"
        )
    return DatabaseTarget(host=host, is_remote=not _is_loopback(host))


def database_identity(dsn: str) -> str:
    parsed = urlsplit(dsn)
    target = database_target(dsn)
    non_secret = {
        "host": target.host.casefold(),
        "port": parsed.port or 5432,
        "database": unquote(parsed.path.lstrip("/")),
        "user": unquote(parsed.username or ""),
    }
    return _hash_text(json.dumps(non_secret, sort_keys=True, separators=(",", ":")))


def _is_loopback(host: str) -> bool:
    if host.casefold() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def require_safe_apply(target: DatabaseTarget, apply: bool, confirmed: bool) -> None:
    if apply and target.is_remote and not confirmed:
        raise BackfillError(
            "Remote database writes require both --apply and --confirm-production"
        )


def load_products(connection: Any, sql: str) -> list[Product]:
    with connection.cursor() as cursor:
        cursor.execute(sql)
        return [
            Product(
                id=int(row[0]),
                brand=str(row[1]),
                model_name=str(row[2]),
                release_date=parse_release_date(row[3]),
            )
            for row in cursor.fetchall()
        ]


def load_missing_products(connection: Any) -> list[Product]:
    return load_products(connection, SELECT_MISSING_SMARTPHONES)


def load_smartphone_catalogue(connection: Any) -> list[Product]:
    return load_products(connection, SELECT_SMARTPHONE_CATALOGUE)


def catalogue_fingerprint(products: Sequence[Product]) -> str:
    payload = [product_dict(product) for product in sorted(products, key=lambda item: item.id)]
    return _hash_text(json.dumps(payload, sort_keys=True, separators=(",", ":")))


def products_by_brand(
    products: Sequence[Product], filters: Sequence[str]
) -> tuple[dict[str, list[Product]], dict[str, str]]:
    by_brand: dict[str, list[Product]] = defaultdict(list)
    display: dict[str, str] = {}
    for product in products:
        key = normalize_name(product.brand)
        if key and normalize_model(product.model_name, product.brand):
            by_brand[key].append(product)
            display.setdefault(key, product.brand.strip())
    requested = {normalize_name(value) for value in filters if normalize_name(value)}
    missing = requested - set(by_brand)
    if missing:
        raise BackfillError(
            "Manufacturer filter did not match missing products: " + ", ".join(sorted(missing))
        )
    if requested:
        by_brand = {key: value for key, value in by_brand.items() if key in requested}
        display = {key: value for key, value in display.items() if key in requested}
    return by_brand, display


def _verify_urls(
    urls: Sequence[str],
    workers: int,
    verifier_factory: Callable[[], PublicImageVerifier],
    on_result: Callable[[str, tuple[bool, str]], None] | None = None,
) -> dict[str, tuple[bool, str]]:
    results: dict[str, tuple[bool, str]] = {}
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="image-verify") as pool:
        futures = {pool.submit(verifier_factory().verify, url): url for url in urls}
        for future in as_completed(futures):
            url = futures[future]
            try:
                results[url] = future.result()
            except Exception:
                results[url] = (False, "image request failed")
            if on_result:
                on_result(url, results[url])
    return results


def prepare_dry_run(
    products: Sequence[Product],
    client: MobileApiClient,
    cache: PersistentCache,
    *,
    manufacturer_filters: Sequence[str] = (),
    acknowledge_unavailable_manufacturers: Sequence[str] = (),
    verification_workers: int = 8,
    verbose: bool = False,
    output: Callable[[str], None] = print,
    verifier_factory: Callable[[], PublicImageVerifier] = PublicImageVerifier,
) -> Summary:
    summary = Summary(total=len(products))
    by_brand, display = products_by_brand(products, manufacturer_filters)
    brand_keys = sorted(by_brand)
    summary.manufacturers_total = len(brand_keys)
    requested_unavailable = {
        normalize_name(value)
        for value in acknowledge_unavailable_manufacturers
        if normalize_name(value)
    }
    unknown_unavailable = requested_unavailable - set(brand_keys)
    if unknown_unavailable:
        raise BackfillError(
            "Unavailable-manufacturer acknowledgement did not match missing products: "
            + ", ".join(sorted(unknown_unavailable))
        )
    previously_unavailable = set(
        cache._manifest().get("unavailable_manufacturers", {})
    )
    already_processed = set(cache._manifest()["processed_manufacturers"])
    for brand_key in requested_unavailable - previously_unavailable:
        brand = display[brand_key]
        listing = cache.manufacturer(brand_key, brand)
        if brand_key in already_processed or listing.get("listing_complete") is True:
            raise BackfillError(
                f"Refusing to mark {brand} unavailable because its listing is complete"
            )

    def progress(brand: str, page: int, total_pages: int | None, cached: bool) -> None:
        suffix = f"/{total_pages}" if total_pages else ""
        source = "cache" if cached else "API"
        output(
            f"  {brand}: page {page}{suffix} [{source}] · "
            f"authenticated requests this run {client.request_count}"
        )

    for position, brand_key in enumerate(brand_keys, start=1):
        brand = display[brand_key]
        brand_products = by_brand[brand_key]
        output(
            f"Manufacturer {position}/{len(brand_keys)}: {brand} "
            f"({len(brand_products)} missing products)"
        )
        if brand_key in requested_unavailable or brand_key in previously_unavailable:
            if brand_key not in previously_unavailable:
                cache.acknowledge_unavailable_manufacturer(
                    brand_key, brand, len(brand_products)
                )
            for product in brand_products:
                cache.save_match(product, "manufacturer_unavailable")
            summary.skipped += len(brand_products)
            summary.manufacturers_completed += 1
            remaining = len(brand_keys) - position
            output(
                f"  Skipped {brand}: provider manufacturer explicitly unavailable · "
                f"manufacturers remaining {remaining}"
            )
            continue
        manufacturer_cache = cache.manufacturer(brand_key, brand)
        try:
            candidates, cached_pages = client.fetch_manufacturer(
                brand,
                cached_pages=manufacturer_cache["pages"],
                save_page=lambda page, payload, key=brand_key, name=brand: (
                    cache.save_manufacturer_page(key, name, page, payload)
                ),
                progress=progress,
            )
            cache.mark_listing_complete(brand_key, brand)
            summary.cached_pages_reused += cached_pages
        except ApiBudgetExhausted as exc:
            cache.record_manufacturer_failure(brand_key, brand, str(exc))
            summary.failed += len(brand_products)
            summary.complete = False
            output(f"Stopped: {exc}")
            break
        except (MobileApiError, CacheValidationError) as exc:
            cache.record_manufacturer_failure(brand_key, brand, str(exc))
            summary.failed += len(brand_products)
            summary.complete = False
            output(f"Failed: {brand} listing ({exc})")
            continue

        matches: dict[int, MatchResult] = {}
        urls_to_verify: set[str] = set()
        verification_values: dict[str, dict[str, Any]] = {}
        for product in brand_products:
            result = match_product(product, candidates)
            matches[product.id] = result
            if result.status != "matched" or result.device is None:
                continue
            summary.matched += 1
            url = result.device.image_url
            if not valid_public_https_url(url):
                continue
            cached_verification = cache.verification(url)
            if cached_verification:
                verification_values[url] = cached_verification
                summary.cached_verifications_reused += 1
            else:
                urls_to_verify.add(url)

        def persist_verification(
            url: str, result: tuple[bool, str]
        ) -> None:
            valid, _reason = result
            if valid:
                verification_values[url] = cache.save_verification(url)

        new_results = _verify_urls(
            sorted(urls_to_verify),
            verification_workers,
            verifier_factory,
            persist_verification,
        )
        if verbose:
            for url in sorted(new_results):
                valid, reason = new_results[url]
                if valid:
                    continue
                output(f"  Image verification failed: {reason}")

        brand_verified = 0
        for product in brand_products:
            result = matches[product.id]
            if result.status == "unmatched":
                summary.skipped += 1
                cache.save_match(product, "unmatched")
                continue
            if result.status == "ambiguous":
                summary.ambiguous += 1
                cache.save_match(product, "ambiguous")
                if verbose:
                    output(f"  Ambiguous: product #{product.id} {product.model_name}")
                continue
            assert result.device is not None
            url = result.device.image_url
            if not valid_public_https_url(url):
                summary.skipped += 1
                cache.save_match(product, "invalid_image", provider=result.device)
                continue
            verification = verification_values.get(url)
            if not verification:
                summary.failed += 1
                cache.save_match(
                    product, "verification_failed", provider=result.device, image_url=url
                )
                continue
            summary.verified += 1
            summary.would_update += 1
            brand_verified += 1
            cache.save_match(
                product,
                "verified",
                provider=result.device,
                image_url=url,
                verified_at=verification["verified_at"],
            )
            if verbose:
                output(f"  Ready: product #{product.id} {product.brand} {product.model_name}")

        cache.mark_manufacturer_processed(brand_key)
        summary.manufacturers_completed += 1
        remaining = len(brand_keys) - position
        output(
            f"  Completed {brand}: {brand_verified} verified · "
            f"manufacturers remaining {remaining}"
        )

    summary.api_requests = client.request_count
    summary.manufacturers_remaining = (
        summary.manufacturers_total - summary.manufacturers_completed
    )
    summary.complete = summary.complete and summary.manufacturers_remaining == 0
    cache.set_last_run(brand_keys, summary.complete)
    return summary


def _cached_product_matches(product: Product, cached: Any) -> bool:
    return isinstance(cached, dict) and cached == product_dict(product)


def cached_updates(
    products: Sequence[Product],
    cache: PersistentCache,
    *,
    manufacturer_filters: Sequence[str] = (),
    output: Callable[[str], None] = print,
) -> tuple[list[PlannedUpdate], Summary]:
    summary = Summary(total=len(products))
    by_brand, _display = products_by_brand(products, manufacturer_filters)
    target_brands = sorted(by_brand)
    summary.manufacturers_total = len(target_brands)
    processed = set(cache._manifest()["processed_manufacturers"])
    incomplete = set(target_brands) - processed
    if incomplete:
        raise CacheValidationError(
            "Cache is incomplete for: " + ", ".join(sorted(incomplete))
            + "; resume the dry run before applying"
        )
    unavailable = cache._manifest().get("unavailable_manufacturers", {})
    for brand_key in target_brands:
        if brand_key in unavailable:
            continue
        listing = cache.manufacturer(brand_key, _display[brand_key])
        if listing.get("listing_complete") is not True:
            raise CacheValidationError(
                f"Cached listing for {_display[brand_key]} is incomplete; "
                "resume the dry run before applying"
            )

    planned: list[PlannedUpdate] = []
    for brand_key in target_brands:
        for product in by_brand[brand_key]:
            cached = cache.match(product.id)
            if not cached or cached.get("status") != "verified":
                summary.skipped += 1
                continue
            if not _cached_product_matches(product, cached.get("product")):
                summary.failed += 1
                output(f"Rejected stale cached product #{product.id}")
                continue
            url = cached.get("image_url")
            if not isinstance(url, str) or not valid_public_https_url(url):
                summary.failed += 1
                continue
            verification = cache.verification(url)
            if (
                not verification
                or verification.get("verified_at") != cached.get("verified_at")
            ):
                summary.failed += 1
                continue
            provider = provider_from_dict(cached.get("provider"))
            rematch = match_product(product, [provider])
            if (
                rematch.status != "matched"
                or rematch.device is None
                or rematch.device.image_url != url
            ):
                summary.failed += 1
                continue
            planned.append(PlannedUpdate(product, url))
            summary.matched += 1
            summary.verified += 1
            summary.cached_matches_reused += 1

    summary.would_update = len(planned)
    summary.manufacturers_completed = len(target_brands)
    summary.complete = summary.failed == 0
    return planned, summary


def apply_updates(
    connection: Any,
    planned: Sequence[PlannedUpdate],
    summary: Summary,
    *,
    apply: bool,
    output: Callable[[str], None] = print,
) -> None:
    if not apply:
        return
    for item in planned:
        try:
            with connection.cursor() as cursor:
                cursor.execute(SELECT_PRODUCT_FOR_UPDATE, (item.product.id,))
                row = cursor.fetchone()
                if not row:
                    summary.failed += 1
                    output(f"Rejected missing product #{item.product.id}")
                    connection.rollback()
                    continue
                current = Product(
                    id=int(row[0]),
                    brand=str(row[1]),
                    model_name=str(row[2]),
                    release_date=parse_release_date(row[4]),
                )
                category, current_image = row[3], row[5]
                if category != "SMARTPHONE" or current != item.product:
                    summary.failed += 1
                    output(f"Rejected changed product #{item.product.id}")
                    connection.rollback()
                    continue
                if current_image is not None:
                    summary.skipped += 1
                    connection.rollback()
                    continue
                cursor.execute(
                    UPDATE_PRODUCT_IMAGE,
                    (
                        item.image_url,
                        item.product.id,
                        item.product.brand,
                        item.product.model_name,
                        item.product.release_date,
                    ),
                )
                if cursor.rowcount == 1:
                    summary.updated += 1
                else:
                    summary.failed += 1
                    output(f"Rejected concurrent change for product #{item.product.id}")
            connection.commit()
        except Exception:
            connection.rollback()
            summary.failed += 1
            output(f"Failed: database update for product #{item.product.id}")


def print_summary(
    summary: Summary,
    apply: bool,
    cache: PersistentCache,
    output: Callable[[str], None] = print,
) -> None:
    manifest = cache._manifest()
    output("")
    output("Summary")
    output(f"  missing products           : {summary.total}")
    output(f"  matched                    : {summary.matched}")
    output(f"  verified                   : {summary.verified}")
    output(f"  would update               : {summary.would_update}")
    output(f"  updated                    : {summary.updated if apply else 0}")
    output(f"  skipped                    : {summary.skipped}")
    output(f"  ambiguous                  : {summary.ambiguous}")
    output(f"  failed                     : {summary.failed}")
    output(
        f"  manufacturers completed    : "
        f"{summary.manufacturers_completed}/{summary.manufacturers_total}"
    )
    output(f"  manufacturers remaining    : {summary.manufacturers_remaining}")
    output(f"  authenticated API requests : {summary.api_requests} this run")
    output(f"  cached pages reused         : {summary.cached_pages_reused}")
    output(f"  cached verifications reused : {summary.cached_verifications_reused}")
    output(f"  cached matches reused       : {summary.cached_matches_reused}")
    output(f"  cache API request total     : {manifest['authenticated_requests_total']}")
    output(f"  cache complete              : {'yes' if summary.complete else 'NO'}")
    if not apply:
        output("  mode                        : DRY RUN (no database writes)")
    elif summary.api_requests == 0:
        output("  MobileAPI during apply      : 0 requests")


def inspect_cache(cache: PersistentCache, output: Callable[[str], None] = print) -> None:
    manifest = cache.load_for_inspection()
    stats = cache.stats()
    last_run = manifest.get("last_run") or {}
    output(f"Cache: {cache.root}")
    output(f"  version                     : {manifest['version']}")
    output(f"  created                     : {manifest['created_at']}")
    output(f"  updated                     : {manifest['updated_at']}")
    output(f"  database identity           : {manifest['database_identity'][:12]}…")
    output(f"  catalogue fingerprint       : {manifest['catalogue_fingerprint'][:12]}…")
    output(f"  completed listings          : {stats['manufacturer_listings']}")
    output(f"  cached API pages            : {stats['pages']}")
    output(f"  verified image URLs         : {stats['verifications']}")
    output(f"  cached product matches      : {stats['matches']}")
    output(
        f"  acknowledged unavailable    : "
        f"{len(manifest.get('unavailable_manufacturers', {}))}"
    )
    output(f"  authenticated requests used : {manifest['authenticated_requests_total']}")
    output(f"  last run complete           : {last_run.get('complete', False)}")
    targets = set(last_run.get("manufacturers") or [])
    processed = set(manifest["processed_manufacturers"])
    unresolved = sorted(targets - processed)
    output(f"  unresolved manufacturers    : {len(unresolved)}")
    for brand_key in unresolved:
        manufacturer = cache.manufacturer(brand_key, brand_key)
        last_error = manufacturer.get("last_error") or {}
        reason = last_error.get("reason", "failure reason was not cached")
        output(f"    - {brand_key}: {reason}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply only validated cached matches. Without this flag, no rows are changed.",
    )
    parser.add_argument(
        "--confirm-production",
        action="store_true",
        help="Explicitly confirm remote/hosted database writes (requires --apply).",
    )
    parser.add_argument(
        "--request-delay",
        type=float,
        default=1.0,
        help="Minimum delay between authenticated MobileAPI requests (default: 1 second).",
    )
    parser.add_argument(
        "--max-api-requests",
        type=int,
        default=1000,
        help="Hard request budget for this run, including retries (default: 1000).",
    )
    parser.add_argument(
        "--verification-workers",
        type=int,
        default=8,
        help="Concurrent anonymous image checks, from 1 to 16 (default: 8).",
    )
    parser.add_argument(
        "--manufacturer",
        action="append",
        default=[],
        help="Process only this manufacturer; repeat for multiple manufacturers.",
    )
    parser.add_argument(
        "--acknowledge-unavailable-manufacturer",
        action="append",
        default=[],
        help=(
            "Explicitly mark an exact missing-product manufacturer as unavailable "
            "from MobileAPI; repeat for multiple manufacturers. No products are matched."
        ),
    )
    parser.add_argument(
        "--offline-cache-only",
        action="store_true",
        help="Forbid MobileAPI requests and use only cached pages.",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=DEFAULT_CACHE_DIR,
        help=f"Persistent cache directory (default: {DEFAULT_CACHE_DIR}).",
    )
    parser.add_argument(
        "--cache-max-age-days",
        type=int,
        default=DEFAULT_CACHE_MAX_AGE_DAYS,
        help="Reject older caches and require refresh (default: 7 days).",
    )
    parser.add_argument(
        "--refresh-cache",
        action="store_true",
        help="Archive the existing cache and start a fresh dry run.",
    )
    parser.add_argument(
        "--inspect-cache",
        action="store_true",
        help="Validate and summarize the cache without a database or API connection.",
    )
    parser.add_argument("--verbose", action="store_true", help="Print per-product results.")
    return parser


def _connect(dsn: str) -> Any:
    try:
        import psycopg
    except ModuleNotFoundError as exc:
        raise BackfillError(
            'psycopg is required; install it with: py -m pip install "psycopg[binary]>=3.1"'
        ) from exc
    try:
        return psycopg.connect(dsn, connect_timeout=10)
    except Exception as exc:
        raise BackfillError("Database connection failed (connection details suppressed)") from exc


def _validate_args(args: argparse.Namespace) -> None:
    if args.max_api_requests < 1:
        raise BackfillError("--max-api-requests must be positive")
    if args.request_delay < 0:
        raise BackfillError("--request-delay cannot be negative")
    if not 1 <= args.verification_workers <= 16:
        raise BackfillError("--verification-workers must be between 1 and 16")
    if args.cache_max_age_days < 1:
        raise BackfillError("--cache-max-age-days must be positive")
    if args.apply and args.refresh_cache:
        raise BackfillError("--refresh-cache cannot be combined with --apply")
    if args.inspect_cache and (args.apply or args.refresh_cache):
        raise BackfillError(
            "--inspect-cache cannot be combined with --apply or --refresh-cache"
        )
    if args.apply and args.acknowledge_unavailable_manufacturer:
        raise BackfillError(
            "Acknowledge unavailable manufacturers in a dry run before --apply"
        )
    if args.refresh_cache and args.offline_cache_only:
        raise BackfillError("--refresh-cache cannot be used with --offline-cache-only")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    cache = PersistentCache(args.cache_dir)
    connection: Any | None = None
    try:
        _validate_args(args)
        if args.inspect_cache:
            inspect_cache(cache)
            return 0

        dsn = os.environ.get("BACKFILL_DATABASE_URL", "").strip()
        if not dsn:
            raise BackfillError("BACKFILL_DATABASE_URL is required; no fallback is used")
        target = database_target(dsn)
        require_safe_apply(target, args.apply, args.confirm_production)

        if args.refresh_cache:
            archived = cache.archive_for_refresh()
            if archived:
                print(f"Archived old cache: {archived}")

        connection = _connect(dsn)
        catalogue = load_smartphone_catalogue(connection)
        cache.bind(database_identity(dsn), catalogue_fingerprint(catalogue))
        cache.ensure_fresh(args.cache_max_age_days)

        mode = "APPLY FROM CACHE" if args.apply else "DRY RUN"
        location = "remote/hosted PostgreSQL" if target.is_remote else "local PostgreSQL"
        print(f"Mode: {mode}")
        print(f"Database: {location} (connection details hidden)")
        print(f"Cache: {cache.root}")

        products = load_missing_products(connection)
        if not products:
            summary = Summary()
        elif args.apply:
            # This path intentionally neither reads MOBILEAPI_API_KEY nor constructs
            # either HTTP client. Applying a validated cache is a zero-API operation.
            planned, summary = cached_updates(
                products,
                cache,
                manufacturer_filters=args.manufacturer,
            )
            apply_updates(connection, planned, summary, apply=True)
        else:
            api_key = os.environ.get("MOBILEAPI_API_KEY", "").strip()
            client = MobileApiClient(
                api_key,
                request_delay=args.request_delay,
                max_requests=args.max_api_requests,
                request_observer=cache.record_authenticated_request,
                offline_cache_only=args.offline_cache_only,
            )
            summary = prepare_dry_run(
                products,
                client,
                cache,
                manufacturer_filters=args.manufacturer,
                acknowledge_unavailable_manufacturers=(
                    args.acknowledge_unavailable_manufacturer
                ),
                verification_workers=args.verification_workers,
                verbose=args.verbose,
            )

        print_summary(summary, args.apply, cache)
        return 1 if summary.failed or not summary.complete else 0

    except (BackfillError, CacheValidationError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2
    except Exception:
        print("Error: backfill failed (sensitive details suppressed)", file=sys.stderr)
        return 2
    finally:
        if connection is not None:
            connection.close()


if __name__ == "__main__":
    sys.exit(main())
