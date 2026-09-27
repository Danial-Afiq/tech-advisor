#!/usr/bin/env python3
"""Validate and optionally load the one-time smartphone catalogue into local Postgres.

The importer deliberately talks only to the repository's local Docker Postgres
container over its Unix socket. It has no remote-database mode.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "data" / "catalogue_backfill.json"
POSTGRES_CONTAINER = "tech-advisor-postgres"
POSTGRES_DATABASE = "techadvisor"
POSTGRES_USER = "techadvisor"
COUNT_TABLES = (
    "products",
    "phone",
    "price_history",
    "benchmark_results",
    "gpu",
    "users",
    "user_devices",
    "device_preferences",
    "market_events",
    "review_documents",
    "review_chunks",
    "recommendations",
    "external_product_mapping",
)
PHONE_FIELDS = (
    "chipset",
    "ram_gb",
    "cpu_ghz",
    "storage_gb",
    "battery_mah",
    "wired_charging_watts",
    "wireless_charging_watts",
    "display_size_inches",
    "refresh_rate_hz",
    "weight_g",
    "camera_specs",
    "pixel_density",
    "ip_rating",
    "os",
    "software_support_years",
)
INTEGER_LIMITS = {
    "ram_gb": (1, 256),
    "storage_gb": (1, 8192),
    "battery_mah": (1, 30000),
    "wired_charging_watts": (1, 1000),
    "wireless_charging_watts": (1, 1000),
    "refresh_rate_hz": (1, 1000),
    "weight_g": (1, 2000),
    "pixel_density": (1, 2000),
}
DECIMAL_LIMITS = {
    "cpu_ghz": (Decimal("0.1"), Decimal("20")),
    "display_size_inches": (Decimal("0.1"), Decimal("20")),
    "software_support_years": (Decimal("0.1"), Decimal("30")),
}
FORBIDDEN_VARIANT_WORDS = (
    "black",
    "white",
    "blue",
    "green",
    "red",
    "gold",
    "silver",
    "titanium",
    "unlocked",
    "verizon",
    "carrier",
    "refurbished",
    "used",
)
PRICE_TYPES = ("DIRECT_SGD", "CONVERTED_TO_SGD")
CURRENT_CANDIDATE_BATCH = "CURRENT_2025_2026_V1"
BENCHMARK_ENRICHMENT_BATCH = "BENCHMARK_COVERAGE_V1"
BENCHMARK_PROVENANCE_TYPES = (
    "DIRECT_DEVICE_RESULT",
    "SHARED_BASE_MODEL_RESULT",
)
BENCHMARK_OBSERVED_AT_SEMANTICS = (
    "SOURCE_PUBLICATION_DATE",
    "CURRENT_REVIEW_PAGE_RETRIEVAL",
)


class ValidationError(RuntimeError):
    """Raised when staging data is unsafe to import."""


def fail(message: str) -> None:
    raise ValidationError(message)


def nonblank(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{label} must be a non-blank string")
    return value.strip()


def parse_iso_date(value: Any, label: str) -> date | None:
    if value is None:
        return None
    try:
        parsed = date.fromisoformat(nonblank(value, label))
    except ValueError as exc:
        fail(f"{label} is not an ISO date: {exc}")
    if parsed > date.today():
        fail(f"{label} cannot be in the future")
    return parsed


def parse_timestamp(value: Any, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(nonblank(value, label).replace("Z", "+00:00"))
    except ValueError as exc:
        fail(f"{label} is not an ISO timestamp: {exc}")
    if parsed.tzinfo is None:
        fail(f"{label} must include a UTC offset")
    return parsed


def parse_decimal(value: Any, label: str) -> Decimal:
    if isinstance(value, bool):
        fail(f"{label} must be numeric")
    try:
        number = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        fail(f"{label} must be numeric: {exc}")
    if not number.is_finite():
        fail(f"{label} must be finite")
    return number


def validate_url(value: Any, label: str) -> str:
    url = nonblank(value, label)
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        fail(f"{label} must be an absolute HTTPS URL")
    return url


def validate_nullable_number(
    value: Any,
    label: str,
    lower: Decimal,
    upper: Decimal,
    *,
    integer: bool,
) -> None:
    if value is None:
        return
    number = parse_decimal(value, label)
    if number < lower or number > upper:
        fail(f"{label} is outside the safe range {lower}..{upper}: {number}")
    if integer and number != number.to_integral_value():
        fail(f"{label} must be an integer")


def validate_document(document: Any) -> dict[str, Any]:
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        fail("staging root must be an object with schema_version 1")
    products = document.get("products")
    if not isinstance(products, list) or len(products) < 200:
        fail("staging must contain at least 200 products")

    identities: set[tuple[str, str]] = set()
    folded_identities: set[tuple[str, str]] = set()
    configurations: set[tuple[str, str, Any, Any]] = set()
    benchmark_keys: set[tuple[str, str, str, str]] = set()
    brands: Counter[str] = Counter()
    priced_brands: Counter[str] = Counter()
    price_types: Counter[str] = Counter()
    original_currencies: Counter[str] = Counter()
    price_count = 0
    benchmark_product_count = 0
    benchmark_count = 0
    benchmark_names: Counter[str] = Counter()
    benchmark_covered_brands: Counter[str] = Counter()
    benchmark_enrichment_count = 0
    benchmark_enrichment_products: set[tuple[str, str]] = set()
    benchmark_enrichment_provenance: Counter[str] = Counter()
    benchmark_enrichment_brands: Counter[str] = Counter()
    current_batch_count = 0
    current_batch_price_types: Counter[str] = Counter()
    current_batch_currencies: Counter[str] = Counter()
    current_batch_brands: Counter[str] = Counter()
    current_batch_years: Counter[str] = Counter()

    for index, product in enumerate(products):
        label = f"products[{index}]"
        if not isinstance(product, dict):
            fail(f"{label} must be an object")
        brand = nonblank(product.get("brand"), f"{label}.brand")
        model = nonblank(product.get("model_name"), f"{label}.model_name")
        base = nonblank(product.get("base_marketed_model"), f"{label}.base_marketed_model")
        if product.get("category") != "SMARTPHONE" or product.get("status") != "VERIFIED":
            fail(f"{label} must be a VERIFIED SMARTPHONE")
        release_date = parse_iso_date(product.get("release_date"), f"{label}.release_date")

        identity = (brand, model)
        folded = (brand.casefold(), model.casefold())
        if identity in identities or folded in folded_identities:
            fail(f"duplicate product identity: {brand} / {model}")
        identities.add(identity)
        folded_identities.add(folded)
        brands[brand] += 1

        lowered_model = model.casefold()
        for word in FORBIDDEN_VARIANT_WORDS:
            if re.search(rf"(?<![a-z]){re.escape(word)}(?![a-z])", lowered_model):
                fail(f"{label}.model_name contains colour/carrier/seller wording: {word}")

        configuration = product.get("configuration")
        phone = product.get("phone")
        if not isinstance(configuration, dict) or not isinstance(phone, dict):
            fail(f"{label} must contain configuration and phone objects")
        if set(phone) != set(PHONE_FIELDS):
            fail(f"{label}.phone has unexpected or missing fields")
        storage = phone.get("storage_gb")
        ram = phone.get("ram_gb")
        if configuration.get("storage_gb") != storage or configuration.get("ram_gb") != ram:
            fail(f"{label} configuration must match its phone row")
        if not re.search(rf"(?<!\d){re.escape(str(storage))}GB$", model):
            fail(f"{label}.model_name does not end with its {storage}GB storage")
        ram_match = re.search(r"(?<!\d)(\d+)GB/(\d+)GB", model)
        if ram_match and (int(ram_match.group(1)) != ram or int(ram_match.group(2)) != storage):
            fail(f"{label}.model_name RAM/storage does not match its phone row")
        config_key = (brand.casefold(), base.casefold(), ram, storage)
        if config_key in configurations:
            fail(f"duplicate hardware configuration: {config_key}")
        configurations.add(config_key)

        for field, (lower, upper) in INTEGER_LIMITS.items():
            validate_nullable_number(
                phone.get(field), f"{label}.phone.{field}", Decimal(lower), Decimal(upper), integer=True
            )
        for field, (lower, upper) in DECIMAL_LIMITS.items():
            validate_nullable_number(phone.get(field), f"{label}.phone.{field}", lower, upper, integer=False)
        for field in ("chipset", "camera_specs", "ip_rating", "os"):
            if phone.get(field) is not None:
                nonblank(phone[field], f"{label}.phone.{field}")

        provenance = product.get("provenance")
        if not isinstance(provenance, dict) or provenance.get("catalogue_record_verified") is not True:
            fail(f"{label} must have verified provenance")
        validate_url(provenance.get("catalogue_record_url"), f"{label}.provenance.catalogue_record_url")
        spec_urls = provenance.get("specification_source_urls")
        if not isinstance(spec_urls, list) or not spec_urls:
            fail(f"{label} must include specification source URLs")
        for source_index, url in enumerate(spec_urls):
            validate_url(url, f"{label}.provenance.specification_source_urls[{source_index}]")
        if product.get("release_date") is not None:
            validate_url(
                provenance.get("release_date_source_url"), f"{label}.provenance.release_date_source_url"
            )
        parse_timestamp(provenance.get("retrieved_at"), f"{label}.provenance.retrieved_at")
        null_fields = provenance.get("intentionally_null_fields")
        if not isinstance(null_fields, list) or set(null_fields) != {
            field for field, value in phone.items() if value is None
        }:
            fail(f"{label}.provenance.intentionally_null_fields must match the phone row")

        catalogue_batch = provenance.get("catalogue_batch")
        if catalogue_batch is not None:
            if catalogue_batch != CURRENT_CANDIDATE_BATCH:
                fail(f"{label}.provenance.catalogue_batch is not recognized")
            if release_date is None or release_date.year not in (2025, 2026):
                fail(f"{label} current-candidate release date must be in 2025 or 2026")
            if ram is None or storage is None:
                fail(f"{label} current-candidate configuration must bind RAM and storage")
            if product.get("price") is None:
                fail(f"{label} current-candidate product must have a current price")
            current_batch_count += 1
            current_batch_brands[brand] += 1
            current_batch_years[str(release_date.year)] += 1

        price = product.get("price")
        if price is not None:
            if not isinstance(price, dict):
                fail(f"{label}.price must be null or an object")
            amount = parse_decimal(price.get("price"), f"{label}.price.price")
            if amount <= 0 or amount > Decimal("1000000"):
                fail(f"{label}.price.price is outside a plausible positive range")
            currency = nonblank(price.get("currency"), f"{label}.price.currency")
            if currency != "SGD":
                fail(f"{label}.price.currency must be SGD after enrichment")
            nonblank(price.get("source"), f"{label}.price.source")
            price_url = validate_url(price.get("source_url"), f"{label}.price.source_url")
            if provenance.get("price_source_url") != price_url:
                fail(f"{label} price provenance does not match its observation")
            observed_at = price.get("observed_at")
            parse_timestamp(observed_at, f"{label}.price.observed_at")

            price_provenance = provenance.get("price_observation")
            if not isinstance(price_provenance, dict):
                fail(f"{label}.provenance.price_observation must be an object")
            price_type = price_provenance.get("price_type")
            if price_type not in PRICE_TYPES:
                fail(f"{label}.provenance.price_observation.price_type is invalid")
            exact_configuration = price_provenance.get("exact_configuration")
            expected_configuration = {
                "brand": brand,
                "model_name": model,
                "ram_gb": ram,
                "storage_gb": storage,
            }
            if exact_configuration != expected_configuration:
                fail(f"{label} price provenance does not identify the exact configuration")
            original_amount = parse_decimal(
                price_provenance.get("original_numeric_price"),
                f"{label}.provenance.price_observation.original_numeric_price",
            )
            if original_amount <= 0:
                fail(f"{label} original price must be positive")
            original_currency = nonblank(
                price_provenance.get("original_currency"),
                f"{label}.provenance.price_observation.original_currency",
            )
            if not re.fullmatch(r"[A-Z]{3}", original_currency):
                fail(f"{label} original currency must be three uppercase letters")
            converted_amount = parse_decimal(
                price_provenance.get("converted_sgd_price"),
                f"{label}.provenance.price_observation.converted_sgd_price",
            )
            if converted_amount != amount:
                fail(f"{label} converted SGD provenance does not match the stored price")
            if price_provenance.get("product_price_source_url") != price_url:
                fail(f"{label} product-price URL does not match the stored price")
            if price_provenance.get("product_price_observed_at") != observed_at:
                fail(f"{label} product-price timestamp does not match the stored price")

            fx_fields = ("fx_rate_to_sgd", "fx_source_url", "fx_rate_date", "fx_retrieved_at")
            if price_type == "DIRECT_SGD":
                if original_currency != "SGD" or original_amount != amount:
                    fail(f"{label} direct SGD provenance must match the stored price")
                if any(price_provenance.get(field) is not None for field in fx_fields):
                    fail(f"{label} direct SGD provenance must not contain FX data")
            else:
                if original_currency == "SGD":
                    fail(f"{label} converted price must preserve its foreign currency")
                fx_rate = parse_decimal(
                    price_provenance.get("fx_rate_to_sgd"),
                    f"{label}.provenance.price_observation.fx_rate_to_sgd",
                )
                if fx_rate <= 0:
                    fail(f"{label} FX rate must be positive")
                validate_url(
                    price_provenance.get("fx_source_url"),
                    f"{label}.provenance.price_observation.fx_source_url",
                )
                parse_iso_date(
                    price_provenance.get("fx_rate_date"),
                    f"{label}.provenance.price_observation.fx_rate_date",
                )
                parse_timestamp(
                    price_provenance.get("fx_retrieved_at"),
                    f"{label}.provenance.price_observation.fx_retrieved_at",
                )
                expected_amount = (original_amount * fx_rate).quantize(
                    Decimal("0.01"), rounding=ROUND_HALF_UP
                )
                if expected_amount != amount:
                    fail(f"{label} converted price does not match its recorded FX calculation")

            if catalogue_batch == CURRENT_CANDIDATE_BATCH:
                listing_text = " ".join(
                    str(price_provenance.get(field) or "")
                    for field in ("source_listing_title", "source_variant")
                )
                if re.search(
                    r"used|pre[- ]?owned|refurb|renewed|lnib|open box|pre[- ]?order|free gift",
                    listing_text,
                    re.IGNORECASE,
                ):
                    fail(f"{label} current-candidate price is not a clean new-device listing")
                storage_patterns = [rf"(?<!\d){storage}\s*GB(?!\d)"]
                if storage % 1024 == 0:
                    storage_patterns.append(rf"(?<!\d){storage // 1024}\s*TB(?!\d)")
                if not any(re.search(pattern, listing_text, re.I) for pattern in storage_patterns):
                    fail(f"{label} price listing does not identify its exact storage")
                if brand != "Apple" and not re.search(rf"(?<!\d){ram}GB(?!\d)", listing_text, re.I):
                    fail(f"{label} price listing does not identify its exact RAM")
                current_batch_price_types[price_type] += 1
                current_batch_currencies[original_currency] += 1

            price_count += 1
            priced_brands[brand] += 1
            price_types[price_type] += 1
            original_currencies[original_currency] += 1
        elif provenance.get("price_source_url") is not None:
            fail(f"{label} has price provenance without a price")
        elif provenance.get("price_observation") is not None:
            fail(f"{label} has detailed price provenance without a price")

        benchmarks = product.get("benchmarks")
        if not isinstance(benchmarks, list):
            fail(f"{label}.benchmarks must be a list")
        provenance_benchmark_urls = provenance.get("benchmark_source_urls")
        if not isinstance(provenance_benchmark_urls, list):
            fail(f"{label}.provenance.benchmark_source_urls must be a list")
        used_benchmark_urls: set[str] = set()
        for benchmark_index, benchmark in enumerate(benchmarks):
            benchmark_label = f"{label}.benchmarks[{benchmark_index}]"
            if not isinstance(benchmark, dict):
                fail(f"{benchmark_label} must be an object")
            name = nonblank(benchmark.get("benchmark_name"), f"{benchmark_label}.benchmark_name")
            score = parse_decimal(benchmark.get("score"), f"{benchmark_label}.score")
            if score <= 0 or score > Decimal("1000000"):
                fail(f"{benchmark_label}.score is outside a plausible positive range")
            unit = nonblank(benchmark.get("unit"), f"{benchmark_label}.unit")
            if benchmark.get("higher_is_better") is not True:
                fail(f"{benchmark_label}.higher_is_better must be true for this dataset")
            source = validate_url(benchmark.get("source"), f"{benchmark_label}.source")
            parse_timestamp(benchmark.get("observed_at"), f"{benchmark_label}.observed_at")
            nonblank(benchmark.get("processor"), f"{benchmark_label}.processor")
            benchmark_key = (brand, model, name, benchmark["observed_at"])
            if benchmark_key in benchmark_keys:
                fail(f"duplicate benchmark observation: {benchmark_key}")
            benchmark_keys.add(benchmark_key)
            used_benchmark_urls.add(source)
            benchmark_count += 1
            benchmark_names[name] += 1

            benchmark_provenance = benchmark.get("provenance")
            if benchmark_provenance is not None:
                if not isinstance(benchmark_provenance, dict):
                    fail(f"{benchmark_label}.provenance must be an object")
                if benchmark_provenance.get("batch_id") != BENCHMARK_ENRICHMENT_BATCH:
                    fail(f"{benchmark_label}.provenance.batch_id is not recognized")
                provenance_type = benchmark_provenance.get("type")
                if provenance_type not in BENCHMARK_PROVENANCE_TYPES:
                    fail(f"{benchmark_label}.provenance.type is invalid")
                nonblank(
                    benchmark_provenance.get("source_name"),
                    f"{benchmark_label}.provenance.source_name",
                )
                if benchmark_provenance.get("source_url") != source:
                    fail(f"{benchmark_label}.provenance.source_url does not match its observation")
                if benchmark_provenance.get("source_base_model") != base:
                    fail(f"{benchmark_label}.provenance.source_base_model does not match the product")
                nonblank(
                    benchmark_provenance.get("benchmark_version_or_test"),
                    f"{benchmark_label}.provenance.benchmark_version_or_test",
                )
                if benchmark_provenance.get("matching_chipset") != phone.get("chipset"):
                    fail(f"{benchmark_label}.provenance.matching_chipset does not match the phone")
                if benchmark_provenance.get("matching_ram_gb") != ram:
                    fail(f"{benchmark_label}.provenance.matching_ram_gb does not match the phone")
                nonblank(
                    benchmark_provenance.get("matching_region"),
                    f"{benchmark_label}.provenance.matching_region",
                )
                parse_timestamp(
                    benchmark_provenance.get("retrieved_at"),
                    f"{benchmark_label}.provenance.retrieved_at",
                )
                if benchmark_provenance.get("observed_at_semantics") not in (
                    BENCHMARK_OBSERVED_AT_SEMANTICS
                ):
                    fail(f"{benchmark_label}.provenance.observed_at_semantics is invalid")
                source_configuration = benchmark_provenance.get("source_configuration")
                if not isinstance(source_configuration, dict):
                    fail(f"{benchmark_label}.provenance.source_configuration must be an object")
                if source_configuration.get("ram_gb") != ram:
                    fail(f"{benchmark_label} source RAM does not match the product")
                if (
                    provenance_type == "DIRECT_DEVICE_RESULT"
                    and source_configuration.get("storage_gb") != storage
                ):
                    fail(f"{benchmark_label} direct result storage does not match the product")
                if provenance_type == "SHARED_BASE_MODEL_RESULT":
                    nonblank(
                        benchmark_provenance.get("propagation_reason"),
                        f"{benchmark_label}.provenance.propagation_reason",
                    )
                benchmark_enrichment_count += 1
                benchmark_enrichment_products.add(identity)
                benchmark_enrichment_provenance[provenance_type] += 1
        if set(provenance_benchmark_urls) != used_benchmark_urls:
            fail(f"{label} benchmark provenance does not match its observations")
        if benchmarks:
            benchmark_product_count += 1
            benchmark_covered_brands[brand] += 1
        if any(
            benchmark.get("provenance", {}).get("batch_id") == BENCHMARK_ENRICHMENT_BATCH
            for benchmark in benchmarks
        ):
            benchmark_enrichment_brands[brand] += 1

    summary = {
        "products": len(products),
        "phone_rows": len(products),
        "prices": price_count,
        "products_with_prices": price_count,
        "products_without_prices": len(products) - price_count,
        "price_types": dict(sorted(price_types.items())),
        "original_price_currencies": dict(sorted(original_currencies.items())),
        "priced_products_per_brand": dict(
            sorted(priced_brands.items(), key=lambda item: item[0].casefold())
        ),
        "benchmark_rows": benchmark_count,
        "products_with_benchmarks": benchmark_product_count,
        "products_without_benchmarks": len(products) - benchmark_product_count,
        "benchmark_names": dict(sorted(benchmark_names.items())),
        "benchmark_covered_products_per_brand": dict(
            sorted(benchmark_covered_brands.items(), key=lambda item: item[0].casefold())
        ),
        "products_per_brand": dict(sorted(brands.items(), key=lambda item: item[0].casefold())),
        "current_candidate_batch": {
            "batch_id": CURRENT_CANDIDATE_BATCH,
            "products": current_batch_count,
            "products_with_prices": sum(current_batch_price_types.values()),
            "price_types": dict(sorted(current_batch_price_types.items())),
            "original_price_currencies": dict(sorted(current_batch_currencies.items())),
            "release_years": dict(sorted(current_batch_years.items())),
            "products_per_brand": dict(
                sorted(current_batch_brands.items(), key=lambda item: item[0].casefold())
            ),
        },
    }

    enrichment = document.get("current_candidate_enrichment")
    if enrichment is not None:
        if not isinstance(enrichment, dict) or enrichment.get("batch_id") != CURRENT_CANDIDATE_BATCH:
            fail("current_candidate_enrichment metadata is invalid")
        expected = {
            "new_products": current_batch_count,
            "new_products_with_prices": sum(current_batch_price_types.values()),
            "direct_sgd": current_batch_price_types["DIRECT_SGD"],
            "converted_to_sgd": current_batch_price_types["CONVERTED_TO_SGD"],
            "release_years": dict(sorted(current_batch_years.items())),
            "products_per_brand": dict(
                sorted(current_batch_brands.items(), key=lambda item: item[0].casefold())
            ),
            "original_price_currencies": dict(sorted(current_batch_currencies.items())),
        }
        for field, expected_value in expected.items():
            if enrichment.get(field) != expected_value:
                fail(f"current_candidate_enrichment.{field} does not match staged records")

    benchmark_enrichment = document.get("benchmark_enrichment")
    if not isinstance(benchmark_enrichment, dict):
        fail("benchmark_enrichment metadata is required")
    if benchmark_enrichment.get("batch_id") != BENCHMARK_ENRICHMENT_BATCH:
        fail("benchmark_enrichment.batch_id is invalid")
    expected_benchmark_enrichment = {
        "added_rows": benchmark_enrichment_count,
        "newly_covered_products": len(benchmark_enrichment_products),
        "provenance_counts": dict(sorted(benchmark_enrichment_provenance.items())),
        "newly_covered_products_per_brand": dict(
            sorted(benchmark_enrichment_brands.items(), key=lambda item: item[0].casefold())
        ),
        "after_benchmark_rows": benchmark_count,
        "after_products_with_benchmarks": benchmark_product_count,
    }
    for field, expected_value in expected_benchmark_enrichment.items():
        if benchmark_enrichment.get(field) != expected_value:
            fail(f"benchmark_enrichment.{field} does not match staged records")
    if benchmark_enrichment.get("before_benchmark_rows") != benchmark_count - benchmark_enrichment_count:
        fail("benchmark_enrichment.before_benchmark_rows does not match staged records")
    if (
        benchmark_enrichment.get("before_products_with_benchmarks")
        != benchmark_product_count - len(benchmark_enrichment_products)
    ):
        fail("benchmark_enrichment.before_products_with_benchmarks does not match staged records")
    return summary


def sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def values_sql(rows: list[list[Any]]) -> str:
    return ",\n".join("(" + ", ".join(sql_literal(value) for value in row) + ")" for row in rows)


def build_import_sql(products: list[dict[str, Any]]) -> str:
    product_values = [
        [p["brand"], p["model_name"], p["category"], p["status"], p["release_date"]] for p in products
    ]
    phone_values = [
        [p["brand"], p["model_name"], *(p["phone"].get(field) for field in PHONE_FIELDS)] for p in products
    ]
    price_values = [
        [p["brand"], p["model_name"], p["price"]["price"], p["price"]["currency"],
         p["price"]["source"], p["price"]["observed_at"]]
        for p in products if p["price"] is not None
    ]
    benchmark_values = [
        [p["brand"], p["model_name"], b["benchmark_name"], b["score"], b["unit"],
         b["higher_is_better"], b["source"], b["observed_at"]]
        for p in products for b in p["benchmarks"]
    ]

    phone_columns = ", ".join(PHONE_FIELDS)
    phone_typed = ",\n       ".join(
        f"{field}::{('integer' if field in INTEGER_LIMITS else 'numeric' if field in DECIMAL_LIMITS else 'text')} AS {field}"
        for field in PHONE_FIELDS
    )
    phone_updates = ",\n        ".join(
        f"{field} = COALESCE(EXCLUDED.{field}, phone.{field})" for field in PHONE_FIELDS
    )
    phone_changed = "\n        OR ".join(
        f"COALESCE(c.{field}, existing.{field}) IS DISTINCT FROM existing.{field}" for field in PHONE_FIELDS
    )
    phone_conflict_changed = "\n        OR ".join(
        f"COALESCE(EXCLUDED.{field}, phone.{field}) IS DISTINCT FROM phone.{field}" for field in PHONE_FIELDS
    )

    return f"""
BEGIN;
CREATE TEMP TABLE backfill_run_stats (
    table_name text PRIMARY KEY,
    inserted bigint NOT NULL,
    updated bigint NOT NULL,
    skipped bigint NOT NULL
);

WITH incoming(brand, model_name, category, status, release_date) AS (
    VALUES
{values_sql(product_values)}
), typed AS (
    SELECT brand::text, model_name::text, category::text, status::text, release_date::date
    FROM incoming
), classified AS (
    SELECT i.*,
           CASE
               WHEN existing.id IS NULL THEN 'inserted'
               WHEN existing.category IS DISTINCT FROM i.category
                 OR existing.status IS DISTINCT FROM i.status
                 OR (i.release_date IS NOT NULL AND existing.release_date IS DISTINCT FROM i.release_date)
                   THEN 'updated'
               ELSE 'skipped'
           END AS action
    FROM typed i
    LEFT JOIN products existing USING (brand, model_name)
), written AS (
    INSERT INTO products (brand, model_name, category, status, release_date)
    SELECT brand, model_name, category, status, release_date
    FROM classified
    ON CONFLICT (brand, model_name) DO UPDATE SET
        category = EXCLUDED.category,
        status = EXCLUDED.status,
        release_date = COALESCE(EXCLUDED.release_date, products.release_date),
        updated_at = CURRENT_TIMESTAMP
    WHERE products.category IS DISTINCT FROM EXCLUDED.category
       OR products.status IS DISTINCT FROM EXCLUDED.status
       OR (EXCLUDED.release_date IS NOT NULL AND products.release_date IS DISTINCT FROM EXCLUDED.release_date)
    RETURNING 1
)
INSERT INTO backfill_run_stats
SELECT 'products',
       COUNT(*) FILTER (WHERE action = 'inserted'),
       COUNT(*) FILTER (WHERE action = 'updated'),
       COUNT(*) FILTER (WHERE action = 'skipped')
FROM classified;

WITH incoming(brand, model_name, {phone_columns}) AS (
    VALUES
{values_sql(phone_values)}
), typed AS (
    SELECT brand::text,
           model_name::text,
           {phone_typed}
    FROM incoming
), classified AS (
    SELECT c.*,
           CASE
               WHEN existing.product_id IS NULL THEN 'inserted'
               WHEN {phone_changed} THEN 'updated'
               ELSE 'skipped'
           END AS action
    FROM typed c
    JOIN products product USING (brand, model_name)
    LEFT JOIN phone existing ON existing.product_id = product.id
), written AS (
    INSERT INTO phone (product_id, {phone_columns})
    SELECT product.id, {phone_columns}
    FROM classified c
    JOIN products product USING (brand, model_name)
    ON CONFLICT (product_id) DO UPDATE SET
        {phone_updates}
    WHERE {phone_conflict_changed}
    RETURNING 1
)
INSERT INTO backfill_run_stats
SELECT 'phone',
       COUNT(*) FILTER (WHERE action = 'inserted'),
       COUNT(*) FILTER (WHERE action = 'updated'),
       COUNT(*) FILTER (WHERE action = 'skipped')
FROM classified;

WITH incoming(brand, model_name, price, currency, source, observed_at) AS (
    VALUES
{values_sql(price_values)}
), typed AS (
    SELECT brand::text, model_name::text, price::numeric(12,2), currency::char(3),
           source::text, observed_at::timestamptz
    FROM incoming
), classified AS (
    SELECT i.*, product.id AS product_id, existing.id AS existing_id,
           CASE
               WHEN existing.id IS NULL THEN 'inserted'
               WHEN existing.price IS DISTINCT FROM i.price
                 OR existing.currency IS DISTINCT FROM i.currency THEN 'updated'
               ELSE 'skipped'
           END AS action
    FROM typed i
    JOIN products product USING (brand, model_name)
    LEFT JOIN LATERAL (
        SELECT observation.id, observation.price, observation.currency
        FROM price_history observation
        WHERE observation.product_id = product.id
          AND observation.source IS NOT DISTINCT FROM i.source
          AND observation.observed_at = i.observed_at
        ORDER BY observation.id
        LIMIT 1
    ) existing ON TRUE
), inserted_rows AS (
    INSERT INTO price_history (product_id, price, currency, source, observed_at)
    SELECT product_id, price, currency, source, observed_at
    FROM classified
    WHERE action = 'inserted'
    RETURNING 1
), updated_rows AS (
    UPDATE price_history observation
    SET price = c.price, currency = c.currency
    FROM classified c
    WHERE c.action = 'updated' AND observation.id = c.existing_id
    RETURNING 1
)
INSERT INTO backfill_run_stats
SELECT 'price_history',
       COUNT(*) FILTER (WHERE action = 'inserted'),
       COUNT(*) FILTER (WHERE action = 'updated'),
       COUNT(*) FILTER (WHERE action = 'skipped')
FROM classified;

WITH incoming(brand, model_name, benchmark_name, score, unit, higher_is_better, source, observed_at) AS (
    VALUES
{values_sql(benchmark_values)}
), typed AS (
    SELECT brand::text, model_name::text, benchmark_name::text, score::numeric,
           unit::text, higher_is_better::boolean, source::text, observed_at::timestamptz
    FROM incoming
), classified AS (
    SELECT i.*, product.id AS product_id, existing.id AS existing_id,
           CASE
               WHEN existing.id IS NULL THEN 'inserted'
               WHEN existing.score IS DISTINCT FROM i.score
                 OR existing.unit IS DISTINCT FROM i.unit
                 OR existing.higher_is_better IS DISTINCT FROM i.higher_is_better THEN 'updated'
               ELSE 'skipped'
           END AS action
    FROM typed i
    JOIN products product USING (brand, model_name)
    LEFT JOIN LATERAL (
        SELECT observation.id, observation.score, observation.unit, observation.higher_is_better
        FROM benchmark_results observation
        WHERE observation.product_id = product.id
          AND observation.benchmark_name = i.benchmark_name
          AND observation.source IS NOT DISTINCT FROM i.source
          AND observation.observed_at = i.observed_at
        ORDER BY observation.id
        LIMIT 1
    ) existing ON TRUE
), inserted_rows AS (
    INSERT INTO benchmark_results
        (product_id, benchmark_name, score, unit, higher_is_better, source, observed_at)
    SELECT product_id, benchmark_name, score, unit, higher_is_better, source, observed_at
    FROM classified
    WHERE action = 'inserted'
    RETURNING 1
), updated_rows AS (
    UPDATE benchmark_results observation
    SET score = c.score,
        unit = c.unit,
        higher_is_better = c.higher_is_better
    FROM classified c
    WHERE c.action = 'updated' AND observation.id = c.existing_id
    RETURNING 1
)
INSERT INTO backfill_run_stats
SELECT 'benchmark_results',
       COUNT(*) FILTER (WHERE action = 'inserted'),
       COUNT(*) FILTER (WHERE action = 'updated'),
       COUNT(*) FILTER (WHERE action = 'skipped')
FROM classified;

SELECT json_build_object(
    'actions', json_object_agg(
        table_name,
        json_build_object('inserted', inserted, 'updated', updated, 'skipped', skipped)
        ORDER BY table_name
    )
)::text
FROM backfill_run_stats;
COMMIT;
"""


def run_command(arguments: list[str], *, stdin: str | None = None) -> str:
    try:
        completed = subprocess.run(
            arguments,
            input=stdin,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=True,
        )
    except FileNotFoundError as exc:
        fail(f"required command is unavailable: {arguments[0]}")
    except subprocess.CalledProcessError as exc:
        message = (exc.stderr or exc.stdout or str(exc)).strip()
        fail(f"command failed without completing the backfill: {message}")
    return completed.stdout.strip()


def run_psql(sql: str) -> str:
    return run_command(
        [
            "docker",
            "exec",
            "-i",
            POSTGRES_CONTAINER,
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "-h",
            "/var/run/postgresql",
            "-U",
            POSTGRES_USER,
            "-d",
            POSTGRES_DATABASE,
        ],
        stdin=sql,
    )


def inspect_local_database() -> dict[str, Any]:
    container = run_command(
        [
            "docker",
            "inspect",
            "--format",
            "{{json .Name}}|{{json .Config.Image}}|{{json .State.Running}}",
            POSTGRES_CONTAINER,
        ]
    )
    name_json, image_json, running_json = container.split("|", 2)
    container_info = {
        "name": json.loads(name_json),
        "image": json.loads(image_json),
        "running": json.loads(running_json),
    }
    if container_info["name"] != f"/{POSTGRES_CONTAINER}" or container_info["running"] is not True:
        fail(f"expected running local container /{POSTGRES_CONTAINER}")
    if not any(name in container_info["image"].casefold() for name in ("postgres", "pgvector")):
        fail("the expected local container is not using a PostgreSQL image")

    database = json.loads(
        run_psql(
            """
SELECT json_build_object(
    'database', current_database(),
    'user', current_user,
    'server_address', inet_server_addr(),
    'server_port', inet_server_port(),
    'version', version(),
    'flyway_version', (SELECT MAX(installed_rank) FROM flyway_schema_history WHERE success),
    'product_variants_table', to_regclass('public.product_variants'),
    'product_variant_column', EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND column_name = 'product_variant_id'
    )
)::text;
"""
        )
    )
    if database["database"] != POSTGRES_DATABASE or database["user"] != POSTGRES_USER:
        fail("database identity is not the expected local techadvisor database")
    if database["server_address"] not in (None, "127.0.0.1", "::1"):
        fail(f"refusing a non-local database server: {database['server_address']}")
    if database["product_variants_table"] is not None or database["product_variant_column"]:
        fail("the database still contains product-variant experiment schema")
    return {"container": container_info, "database": database}


def table_counts() -> dict[str, int]:
    pairs = ", ".join(f"'{table}', (SELECT COUNT(*) FROM {table})" for table in COUNT_TABLES)
    return json.loads(run_psql(f"SELECT json_build_object({pairs})::text;"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="normalized staging JSON")
    parser.add_argument(
        "--apply",
        action="store_true",
        help="write to the fixed local Docker Postgres container after validation",
    )
    args = parser.parse_args()

    try:
        document = json.loads(args.data.read_text(encoding="utf-8"))
        summary = validate_document(document)
        print(json.dumps({"validation": "PASSED", "dataset": summary}, indent=2))
        if not args.apply:
            print("Validation only: no database connection or write was made.")
            return 0

        target = inspect_local_database()
        before = table_counts()
        action_output = run_psql(build_import_sql(document["products"]))
        actions = json.loads(action_output)
        after = table_counts()
        print(json.dumps({"target": target, "before": before, **actions, "after": after}, indent=2))
        return 0
    except (OSError, json.JSONDecodeError, ValidationError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
