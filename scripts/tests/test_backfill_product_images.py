from __future__ import annotations

import json
import sys
import unittest
from datetime import date
from email.message import Message
from pathlib import Path
from urllib.error import HTTPError


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import backfill_product_images as backfill


class FakeResponse:
    def __init__(self, payload=b"", *, url="https://example.test/image.png", content_type="application/json"):
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
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class RejectsProbeRequestsOpener:
    """Models an image origin that rejects HEAD/Range but serves browser GET."""

    def __init__(self):
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append((request, timeout))
        if request.get_method() != "GET" or request.get_header("Range"):
            raise HTTPError(request.full_url, 406, "not acceptable", Message(), None)
        return FakeResponse(
            url=request.full_url,
            content_type="image/png",
        )


class RecordingCursor:
    def __init__(self, connection):
        self.connection = connection
        self.rowcount = -1

    def execute(self, sql, params=None):
        self.connection.executions.append((sql, params))
        if sql == backfill.SELECT_PRODUCT_FOR_UPDATE:
            self.rows = [self.connection.current_rows.get(params[0])]
        if sql == backfill.UPDATE_PRODUCT_IMAGE:
            self.rowcount = self.connection.rowcounts.pop(0)

    def fetchall(self):
        return self.connection.rows

    def fetchone(self):
        return self.rows[0] if getattr(self, "rows", None) else None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class RecordingConnection:
    def __init__(self, *, rows=(), rowcounts=(), current_rows=None):
        self.rows = list(rows)
        self.rowcounts = list(rowcounts)
        self.executions = []
        self.commits = 0
        self.rollbacks = 0
        self.current_rows = dict(current_rows or {})

    def cursor(self):
        return RecordingCursor(self)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


def json_response(value):
    return FakeResponse(json.dumps(value).encode("utf-8"))


class MatchingTests(unittest.TestCase):
    def test_exact_normalized_brand_and_model_match(self):
        product = backfill.Product(6260, "Samsung", "Samsung Galaxy S25", date(2025, 2, 7))
        device = backfill.ProviderDevice(
            "31333",
            "SAMSUNG",
            "Galaxy S25",
            date(2025, 2, 7),
            "https://images.example.test/s25.png",
        )

        result = backfill.match_product(product, [device])

        self.assertEqual("matched", result.status)
        self.assertEqual(device, result.device)

    def test_release_date_resolves_duplicate_exact_names(self):
        product = backfill.Product(1, "Acme", "One", date(2025, 3, 1))
        candidates = [
            backfill.ProviderDevice("old", "Acme", "One", date(2024, 3, 1), "https://x.test/old.png"),
            backfill.ProviderDevice("new", "Acme", "One", date(2025, 3, 1), "https://x.test/new.png"),
        ]

        result = backfill.match_product(product, candidates)

        self.assertEqual("new", result.device.provider_id)

    def test_ambiguous_and_missing_matches_are_not_guessed(self):
        product = backfill.Product(1, "Acme", "One")
        duplicate = backfill.ProviderDevice("a", "Acme", "One", None, "https://x.test/a.png")
        other = backfill.ProviderDevice("b", "Acme", "One", None, "https://x.test/b.png")

        self.assertEqual("ambiguous", backfill.match_product(product, [duplicate, other]).status)
        self.assertEqual("unmatched", backfill.match_product(product, []).status)

    def test_unique_name_with_conflicting_release_year_is_rejected(self):
        product = backfill.Product(1, "Acme", "One", date(2025, 1, 1))
        candidate = backfill.ProviderDevice(
            "a", "Acme", "One", date(2023, 1, 1), "https://x.test/a.png"
        )

        self.assertEqual("unmatched", backfill.match_product(product, [candidate]).status)

    def test_missing_or_invalid_image_urls_are_rejected(self):
        self.assertFalse(backfill.valid_public_https_url(None))
        self.assertFalse(backfill.valid_public_https_url("http://images.example.test/a.png"))
        self.assertFalse(backfill.valid_public_https_url("https://images.example.test/a.png?key=secret"))
        self.assertFalse(backfill.valid_public_https_url("https://127.0.0.1/a.png"))
        self.assertFalse(backfill.valid_public_https_url("https://service.local/a.png"))
        self.assertTrue(backfill.valid_public_https_url("https://images.example.test/a.png"))


class MobileApiClientTests(unittest.TestCase):
    def test_paginates_with_maximum_limit_and_keeps_key_out_of_url(self):
        opener = FakeOpener(
            json_response(
                {
                    "page": 1,
                    "total_pages": 2,
                    "has_next": True,
                    "devices": [
                        {
                            "id": 1,
                            "name": "Galaxy S25",
                            "manufacturer_name": "Samsung",
                            "release_date": "2025-02-07",
                            "image_url": "https://images.example.test/s25.png",
                        }
                    ],
                }
            ),
            json_response({"page": 2, "total_pages": 2, "has_next": False, "devices": []}),
        )
        client = backfill.MobileApiClient(
            "top-secret", opener=opener, request_delay=0, sleep=lambda _seconds: None
        )

        devices, cached = client.fetch_manufacturer("Samsung")

        self.assertEqual(1, len(devices))
        self.assertEqual(0, cached)
        self.assertEqual(2, client.request_count)
        first_request = opener.requests[0][0]
        self.assertIn("limit=50", first_request.full_url)
        self.assertIn("page=1", first_request.full_url)
        self.assertNotIn("top-secret", first_request.full_url)
        self.assertEqual("Token top-secret", first_request.get_header("Authorization"))

    def test_retries_transient_failure_then_succeeds(self):
        headers = Message()
        headers["Retry-After"] = "0"
        opener = FakeOpener(
            HTTPError("https://api.mobileapi.dev", 429, "rate limit", headers, None),
            json_response({"page": 1, "total_pages": 1, "devices": []}),
        )
        client = backfill.MobileApiClient(
            "key", opener=opener, request_delay=0, sleep=lambda _seconds: None
        )

        self.assertEqual([], client.fetch_manufacturer("Samsung")[0])
        self.assertEqual(2, client.request_count)

    def test_reports_api_failure_after_bounded_retries(self):
        errors = [
            HTTPError("https://api.mobileapi.dev", 503, "down", Message(), None)
            for _ in range(3)
        ]
        client = backfill.MobileApiClient(
            "key",
            opener=FakeOpener(*errors),
            request_delay=0,
            max_retries=2,
            sleep=lambda _seconds: None,
        )

        with self.assertRaises(backfill.MobileApiError):
            client.fetch_manufacturer("Samsung")
        self.assertEqual(3, client.request_count)


class ImageVerificationTests(unittest.TestCase):
    def test_public_image_check_uses_anonymous_browser_get_not_head_or_range(self):
        opener = RejectsProbeRequestsOpener()
        verifier = backfill.PublicImageVerifier(opener=opener)

        valid, _reason = verifier.verify("https://cdn.example.test/phone.png")

        self.assertTrue(valid)
        request = opener.requests[0][0]
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Range"))
        self.assertIsNone(request.get_header("Referer"))
        self.assertEqual("GET", request.get_method())
        self.assertEqual(backfill.BROWSER_IMAGE_ACCEPT, request.get_header("Accept"))
        self.assertEqual(1, len(opener.requests))

    def test_browser_get_406_is_still_a_verification_failure(self):
        error = HTTPError(
            "https://cdn.example.test/phone.png",
            406,
            "not acceptable",
            Message(),
            None,
        )
        opener = FakeOpener(error)
        verifier = backfill.PublicImageVerifier(opener=opener)

        valid, reason = verifier.verify("https://cdn.example.test/phone.png")

        self.assertFalse(valid)
        self.assertEqual("HTTP 406", reason)
        self.assertEqual(1, len(opener.requests))


class DatabaseSafetyTests(unittest.TestCase):
    def test_query_selects_only_missing_smartphones(self):
        connection = RecordingConnection(rows=[(6260, "Samsung", "Galaxy S25", date(2025, 2, 7))])

        products = backfill.load_missing_products(connection)

        self.assertEqual([6260], [product.id for product in products])
        sql = " ".join(connection.executions[0][0].split())
        self.assertIn("category = 'SMARTPHONE'", sql)
        self.assertIn("image_url IS NULL", sql)

    def test_dry_run_performs_no_database_update(self):
        connection = RecordingConnection(rowcounts=[1])
        summary = backfill.Summary(would_update=1)
        planned = [
            backfill.PlannedUpdate(
                backfill.Product(6260, "Samsung", "Galaxy S25"),
                "https://images.example.test/s25.png",
            )
        ]

        backfill.apply_updates(connection, planned, summary, apply=False)

        self.assertEqual([], connection.executions)
        self.assertEqual(0, connection.commits)
        self.assertEqual(0, summary.updated)

    def test_update_is_null_only_and_idempotent(self):
        product_row = (6260, "Samsung", "Galaxy S25", "SMARTPHONE", None, None)
        connection = RecordingConnection(
            rowcounts=[1, 0], current_rows={6260: product_row}
        )
        planned = [
            backfill.PlannedUpdate(
                backfill.Product(6260, "Samsung", "Galaxy S25"),
                "https://images.example.test/s25.png",
            )
        ]
        first = backfill.Summary()
        second = backfill.Summary()

        backfill.apply_updates(connection, planned, first, apply=True, output=lambda _line: None)
        backfill.apply_updates(connection, planned, second, apply=True, output=lambda _line: None)

        self.assertEqual(1, first.updated)
        self.assertEqual(0, second.updated)
        self.assertEqual(1, second.failed)
        self.assertEqual(2, connection.commits)
        updates = [item for item in connection.executions if item[0] == backfill.UPDATE_PRODUCT_IMAGE]
        for sql, params in updates:
            normalized = " ".join(sql.split())
            self.assertEqual(
                "UPDATE products SET image_url = %s WHERE id = %s "
                "AND category = 'SMARTPHONE' AND image_url IS NULL "
                "AND brand = %s AND model_name = %s "
                "AND release_date IS NOT DISTINCT FROM %s",
                normalized,
            )
            self.assertEqual(
                ("https://images.example.test/s25.png", 6260, "Samsung", "Galaxy S25", None),
                params,
            )

    def test_remote_apply_requires_explicit_confirmation_not_database_name(self):
        local = backfill.database_target(
            "postgresql://user:password@127.0.0.1/anything"
        )
        remote = backfill.database_target(
            "postgresql://user:password@ep-example.neon.tech/techadvisor_test?sslmode=require"
        )

        self.assertFalse(local.is_remote)
        self.assertTrue(remote.is_remote)
        with self.assertRaises(backfill.BackfillError):
            backfill.require_safe_apply(remote, apply=True, confirmed=False)
        backfill.require_safe_apply(remote, apply=True, confirmed=True)


if __name__ == "__main__":
    unittest.main()
