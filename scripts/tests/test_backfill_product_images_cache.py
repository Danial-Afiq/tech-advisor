from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from email.message import Message
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError, URLError


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import backfill_product_images as backfill


class FakeResponse:
    def __init__(self, payload=b"", *, content_type="application/json", url="https://x.test/i.png"):
        self.payload = payload
        self.url = url
        self.headers = Message()
        self.headers["Content-Type"] = content_type

    def read(self):
        return self.payload

    def geturl(self):
        return self.url

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeOpener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append((request, timeout))
        if not self.responses:
            raise AssertionError("Unexpected HTTP request")
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class ValidVerifier:
    def verify(self, _url):
        return True, "public image"


def json_response(value):
    return FakeResponse(json.dumps(value).encode("utf-8"))


def page(number=1, total=1):
    return {
        "page": number,
        "total_pages": total,
        "has_next": number < total,
        "devices": [{
            "id": 31333,
            "name": "Galaxy S25",
            "manufacturer_name": "Samsung",
            "release_date": "2025-02-07",
            "image_url": "https://images.example.test/s25.png",
        }],
    }


def verified_cache(root, dsn, product):
    cache = backfill.PersistentCache(root)
    cache.bind(backfill.database_identity(dsn), backfill.catalogue_fingerprint([product]))
    cache.save_manufacturer_page("samsung", "Samsung", 1, page())
    cache.mark_listing_complete("samsung", "Samsung")
    url = "https://images.example.test/s25.png"
    verification = cache.save_verification(url)
    provider = backfill.ProviderDevice(
        "31333", "Samsung", "Galaxy S25", date(2025, 2, 7), url
    )
    cache.save_match(
        product, "verified", provider=provider, image_url=url,
        verified_at=verification["verified_at"],
    )
    cache.mark_manufacturer_processed("samsung")
    return cache


class FakeCursor:
    def __init__(self, connection):
        self.connection = connection
        self.rows = []
        self.rowcount = -1

    def execute(self, sql, params=None):
        self.connection.executions.append((sql, params))
        self.rowcount = -1
        if sql == backfill.SELECT_SMARTPHONE_CATALOGUE:
            self.rows = list(self.connection.catalogue)
        elif sql == backfill.SELECT_MISSING_SMARTPHONES:
            self.rows = list(self.connection.missing)
        elif sql == backfill.SELECT_PRODUCT_FOR_UPDATE:
            row = self.connection.current.get(params[0])
            self.rows = [row] if row else []
        elif sql == backfill.UPDATE_PRODUCT_IMAGE:
            row = self.connection.current.get(params[1])
            if row and row[5] is None:
                self.rowcount = 1
                self.connection.current[params[1]] = (*row[:5], params[0])
            else:
                self.rowcount = 0

    def fetchall(self):
        return self.rows

    def fetchone(self):
        return self.rows[0] if self.rows else None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeConnection:
    def __init__(self, catalogue, missing, current):
        self.catalogue = catalogue
        self.missing = missing
        self.current = current
        self.executions = []
        self.commits = 0
        self.rollbacks = 0
        self.closed = False

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1

    def close(self):
        self.closed = True


class ProPlanClientTests(unittest.TestCase):
    def test_pro_defaults_are_bounded_and_configurable(self):
        defaults = backfill.build_parser().parse_args([])
        self.assertEqual(1.0, defaults.request_delay)
        self.assertEqual(1000, defaults.max_api_requests)
        self.assertEqual(8, defaults.verification_workers)

    def test_budget_counts_retries_and_stops_before_next_request(self):
        client = backfill.MobileApiClient(
            "secret",
            opener=FakeOpener(json_response(page(1, 2))),
            request_delay=0,
            max_requests=1,
            sleep=lambda _seconds: None,
        )
        with self.assertRaises(backfill.ApiBudgetExhausted):
            client.fetch_manufacturer("Samsung")
        self.assertEqual(1, client.request_count)

    def test_resume_uses_cached_page_and_fetches_only_missing_page(self):
        cached_page = {"fetched_at": backfill.now_utc(), "payload": page(1, 2)}
        opener = FakeOpener(
            json_response({"page": 2, "total_pages": 2, "devices": []})
        )
        client = backfill.MobileApiClient(
            "secret", opener=opener, request_delay=0, sleep=lambda _seconds: None
        )

        devices, reused = client.fetch_manufacturer(
            "Samsung", cached_pages={"1": cached_page}
        )

        self.assertEqual(1, len(devices))
        self.assertEqual(1, reused)
        self.assertEqual(1, client.request_count)
        self.assertIn("page=2", opener.requests[0][0].full_url)

    def test_request_observer_counts_every_retry(self):
        headers = Message()
        headers["Retry-After"] = "0"
        observed = []
        client = backfill.MobileApiClient(
            "secret",
            opener=FakeOpener(
                HTTPError("https://api.mobileapi.dev", 429, "limited", headers, None),
                json_response({"page": 1, "total_pages": 1, "devices": []}),
            ),
            request_delay=0,
            sleep=lambda _seconds: None,
            request_observer=observed.append,
        )
        client.fetch_manufacturer("Samsung")
        self.assertEqual([1, 2], observed)

    def test_image_verification_retries_only_transient_failure(self):
        opener = FakeOpener(
            URLError("temporary"),
            FakeResponse(content_type="image/webp", url="https://x.test/i.png"),
        )
        verifier = backfill.PublicImageVerifier(
            opener=opener, sleep=lambda _seconds: None
        )
        self.assertTrue(verifier.verify("https://x.test/i.png")[0])
        self.assertEqual(2, len(opener.requests))


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "cache"
        self.product = backfill.Product(
            6260, "Samsung", "Galaxy S25", date(2025, 2, 7)
        )

    def tearDown(self):
        self.temp.cleanup()

    def test_cache_persists_pages_verification_and_match(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", "catalogue-a")
        cache.save_manufacturer_page("samsung", "Samsung", 1, page())
        cache.mark_listing_complete("samsung", "Samsung")
        verification = cache.save_verification("https://images.example.test/s25.png")
        provider = backfill.ProviderDevice(
            "31333", "Samsung", "Galaxy S25", date(2025, 2, 7),
            "https://images.example.test/s25.png",
        )
        cache.save_match(
            self.product, "verified", provider=provider,
            image_url=provider.image_url, verified_at=verification["verified_at"],
        )

        loaded = backfill.PersistentCache(self.root)
        loaded.bind("database-a", "catalogue-a")
        self.assertEqual(1, len(loaded.manufacturer("samsung", "Samsung")["pages"]))
        self.assertTrue(loaded.verification(provider.image_url)["verified"])
        self.assertEqual("verified", loaded.match(6260)["status"])

    def test_database_and_catalogue_identity_mismatches_fail(self):
        backfill.PersistentCache(self.root).bind("database-a", "catalogue-a")
        with self.assertRaises(backfill.CacheValidationError):
            backfill.PersistentCache(self.root).bind("database-b", "catalogue-a")
        with self.assertRaises(backfill.CacheValidationError):
            backfill.PersistentCache(self.root).bind("database-a", "catalogue-b")

    def test_stale_or_corrupt_cache_fails_safely(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", "catalogue-a")
        manifest = json.loads(cache.manifest_path.read_text(encoding="utf-8"))
        manifest["created_at"] = (
            datetime.now(timezone.utc) - timedelta(days=8)
        ).isoformat()
        backfill._atomic_json(cache.manifest_path, manifest)
        loaded = backfill.PersistentCache(self.root)
        loaded.bind("database-a", "catalogue-a")
        with self.assertRaises(backfill.CacheValidationError):
            loaded.ensure_fresh(7)

        cache.manifest_path.write_text("{partial", encoding="utf-8")
        with self.assertRaises(backfill.CacheValidationError):
            backfill.PersistentCache(self.root).load_for_inspection()

    def test_refresh_archives_instead_of_deleting_cache(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", "catalogue-a")
        archived = cache.archive_for_refresh()
        self.assertFalse(self.root.exists())
        self.assertTrue(archived.exists())

    def test_interrupted_run_resumes_incrementally(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", "catalogue-a")
        first_client = backfill.MobileApiClient(
            "secret",
            opener=FakeOpener(json_response(page(1, 2))),
            request_delay=0,
            max_requests=1,
            sleep=lambda _seconds: None,
            request_observer=cache.record_authenticated_request,
        )
        first = backfill.prepare_dry_run(
            [self.product], first_client, cache,
            verifier_factory=ValidVerifier, output=lambda _line: None,
        )
        self.assertFalse(first.complete)

        second_client = backfill.MobileApiClient(
            "secret",
            opener=FakeOpener(
                json_response({"page": 2, "total_pages": 2, "devices": []})
            ),
            request_delay=0,
            sleep=lambda _seconds: None,
            request_observer=cache.record_authenticated_request,
        )
        second = backfill.prepare_dry_run(
            [self.product], second_client, cache,
            verifier_factory=ValidVerifier, output=lambda _line: None,
        )

        self.assertTrue(second.complete)
        self.assertEqual(1, second.cached_pages_reused)
        self.assertEqual(1, second.would_update)
        self.assertEqual(2, cache._manifest()["authenticated_requests_total"])

    def test_apply_plan_revalidates_cached_product_match_and_image(self):
        dsn = "postgresql://user:password@127.0.0.1/techadvisor"
        cache = verified_cache(self.root, dsn, self.product)

        planned, summary = backfill.cached_updates(
            [self.product], cache, output=lambda _line: None
        )
        self.assertEqual([6260], [item.product.id for item in planned])
        self.assertEqual(1, summary.cached_matches_reused)

        changed = backfill.Product(
            6260, "Samsung", "Galaxy S25", date(2024, 2, 7)
        )
        rejected, rejected_summary = backfill.cached_updates(
            [changed], cache, output=lambda _line: None
        )
        self.assertEqual([], rejected)
        self.assertEqual(1, rejected_summary.failed)

    def test_incomplete_manufacturer_cache_cannot_be_applied(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", backfill.catalogue_fingerprint([self.product]))
        with self.assertRaises(backfill.CacheValidationError):
            backfill.cached_updates([self.product], cache, output=lambda _line: None)

    def test_unavailable_manufacturer_can_be_acknowledged_offline(self):
        product = backfill.Product(9001, "Dashboard Demo", "Demo Phone")
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", backfill.catalogue_fingerprint([product]))
        client = backfill.MobileApiClient(
            "",
            opener=FakeOpener(),
            offline_cache_only=True,
            sleep=lambda _seconds: None,
        )

        summary = backfill.prepare_dry_run(
            [product],
            client,
            cache,
            acknowledge_unavailable_manufacturers=["Dashboard Demo"],
            output=lambda _line: None,
        )

        self.assertTrue(summary.complete)
        self.assertEqual(0, client.request_count)
        self.assertEqual(1, summary.skipped)
        self.assertIn("dashboard demo", cache._manifest()["unavailable_manufacturers"])
        self.assertEqual("manufacturer_unavailable", cache.match(9001)["status"])
        planned, apply_summary = backfill.cached_updates(
            [product], cache, output=lambda _line: None
        )
        self.assertEqual([], planned)
        self.assertTrue(apply_summary.complete)

    def test_complete_listing_cannot_be_reclassified_as_unavailable(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", backfill.catalogue_fingerprint([self.product]))
        cache.save_manufacturer_page("samsung", "Samsung", 1, page())
        cache.mark_listing_complete("samsung", "Samsung")
        cache.mark_manufacturer_processed("samsung")
        client = backfill.MobileApiClient(
            "", opener=FakeOpener(), offline_cache_only=True
        )

        with self.assertRaises(backfill.BackfillError):
            backfill.prepare_dry_run(
                [self.product],
                client,
                cache,
                acknowledge_unavailable_manufacturers=["Samsung"],
                output=lambda _line: None,
            )

    def test_offline_cache_miss_records_exact_failure_without_http(self):
        cache = backfill.PersistentCache(self.root)
        cache.bind("database-a", backfill.catalogue_fingerprint([self.product]))
        client = backfill.MobileApiClient(
            "",
            opener=FakeOpener(),
            offline_cache_only=True,
            sleep=lambda _seconds: None,
        )

        summary = backfill.prepare_dry_run(
            [self.product], client, cache, output=lambda _line: None
        )

        self.assertFalse(summary.complete)
        self.assertEqual(0, client.request_count)
        manufacturer = cache.manufacturer("samsung", "Samsung")
        self.assertIn("offline cache-only mode", manufacturer["last_error"]["reason"])


class ApplyFromCacheTests(unittest.TestCase):
    def test_apply_from_cache_constructs_no_mobileapi_client(self):
        product = backfill.Product(
            6260, "Samsung", "Galaxy S25", date(2025, 2, 7)
        )
        dsn = "postgresql://user:password@127.0.0.1/techadvisor"
        row = (6260, "Samsung", "Galaxy S25", date(2025, 2, 7))
        with tempfile.TemporaryDirectory() as temp:
            cache_dir = Path(temp) / "cache"
            verified_cache(cache_dir, dsn, product)
            connection = FakeConnection(
                catalogue=[row],
                missing=[row],
                current={6260: (6260, "Samsung", "Galaxy S25", "SMARTPHONE", row[3], None)},
            )
            with mock.patch.dict(
                os.environ, {"BACKFILL_DATABASE_URL": dsn}, clear=True
            ), mock.patch.object(
                backfill, "_connect", return_value=connection
            ), mock.patch.object(
                backfill,
                "MobileApiClient",
                side_effect=AssertionError("MobileAPI client must not be constructed"),
            ):
                result = backfill.main(
                    ["--apply", "--cache-dir", str(cache_dir)]
                )

        self.assertEqual(0, result)
        self.assertEqual(1, connection.commits)
        self.assertTrue(connection.closed)

    def test_apply_rechecks_row_fields_inside_transaction(self):
        cached = backfill.Product(
            6260, "Samsung", "Galaxy S25", date(2025, 2, 7)
        )
        changed_row = (
            6260, "Samsung", "Galaxy S25 Ultra", "SMARTPHONE", date(2025, 2, 7), None
        )
        connection = FakeConnection([], [], {6260: changed_row})
        summary = backfill.Summary()

        backfill.apply_updates(
            connection,
            [backfill.PlannedUpdate(cached, "https://images.example.test/s25.png")],
            summary,
            apply=True,
            output=lambda _line: None,
        )

        self.assertEqual(1, summary.failed)
        self.assertFalse(any(sql == backfill.UPDATE_PRODUCT_IMAGE for sql, _ in connection.executions))


if __name__ == "__main__":
    unittest.main()
