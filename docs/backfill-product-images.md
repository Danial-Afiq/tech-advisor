# Backfill missing smartphone images

`scripts/backfill_product_images.py` is a one-time, dry-run-first utility for
smartphone rows whose `products.image_url` is still null. It does not run as
part of the backend or normal ingestion.

The script:

- reads only existing `SMARTPHONE` rows with a null image;
- fetches each required MobileAPI manufacturer listing, following its pagination
  at the documented maximum of 50 devices per page;
- matches exact normalized brand/model names and uses release dates only to
  resolve duplicate exact names;
- anonymously verifies each proposed HTTPS URL with the same ordinary GET shape
  as the frontend's `<img>` element (no API key, referrer, HEAD, or Range probe);
- saves pages, successful anonymous image checks, and product matches to an
  atomic local cache as work completes;
- updates only `products.image_url`, after rechecking category, null value,
  brand, model and release date;
- is a dry run unless `--apply` is supplied; `--apply` uses the cache and makes
  zero MobileAPI or image-verification requests.

The Pro-oriented defaults are a one-second delay and a hard budget of 1,000
authenticated requests per run. The budget includes retries and is independent
of the account's remaining monthly quota. The script never makes unlimited
requests, honors exhausted-rate reset headers, and backs off on HTTP 429 and
transient failures. Adjust the defaults explicitly with `--request-delay` and
`--max-api-requests`. See the
[official MobileAPI documentation](https://api.mobileapi.dev/docs/).

The default cache is `.backfill-cache/product-images-v2`, which Git ignores.
It contains hashes identifying the database target and complete smartphone
catalogue, but no API key, database URL, username/password combination, or
other credentials. Cache entries expire after seven days by default. A cache
from another database/catalogue, a stale cache, corrupt JSON, an incomplete
manufacturer run, or a changed product is rejected before writes.

## Prerequisites

From the repository root, install the PostgreSQL driver once:

```powershell
py -m pip install "psycopg[binary]>=3.1"
```

Set the MobileAPI key in the current terminal for dry runs. The script sends it
in the authorization header and never prints or caches it:

```powershell
$env:MOBILEAPI_API_KEY = '<your MobileAPI key>'
```

## Local PostgreSQL

Start the existing database, then set the dedicated connection URL. Adjust the
port or password if your root `.env` differs from `.env.example`.

```powershell
docker compose up -d postgres
$env:BACKFILL_DATABASE_URL = 'postgresql://techadvisor:devpassword@127.0.0.1:5434/techadvisor'
```

Dry run (no database writes):

```powershell
py .\scripts\backfill_product_images.py
```

Apply the reviewed local cache (no MobileAPI key or HTTP request is needed):

```powershell
Remove-Item Env:MOBILEAPI_API_KEY -ErrorAction SilentlyContinue
py .\scripts\backfill_product_images.py --apply
```

## Complete hosted Neon run (Pro settings)

Copy the PostgreSQL connection string from Neon and retain
`sslmode=require`. Do not paste the value into source files, command history
that is shared, screenshots, or issue comments.

```powershell
$env:BACKFILL_DATABASE_URL = 'postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require'
```

Set the key only in the current terminal, then run the complete dry run. These
flags repeat the defaults deliberately so the intended rate and finite request
budget are visible before the run:

```powershell
$env:MOBILEAPI_API_KEY = '<your MobileAPI key>'
py .\scripts\backfill_product_images.py --request-delay 1 --max-api-requests 1000 --verification-workers 8
```

This reads Neon catalogue rows but makes no database writes. It processes every
manufacturer represented among missing smartphone images. To restrict a run,
add `--manufacturer Samsung`; repeat the option for more than one manufacturer.

### Resume an interrupted or budget-limited dry run

Run the same command with the same database URL and cache directory:

```powershell
py .\scripts\backfill_product_images.py --request-delay 1 --max-api-requests 1000 --verification-workers 8
```

Already saved pages and successful image verifications are reused. New pages,
verifications, and matches are written atomically as they finish. If the request
budget is exhausted, the summary says the cache is incomplete and the command
returns a nonzero status; the next run resumes from the first uncached page.

### Inspect the cache and request usage

This command needs neither database nor API credentials:

```powershell
py .\scripts\backfill_product_images.py --inspect-cache
```

It validates all cache JSON and reports cached pages, completed manufacturer
listings, verified URLs, product matches, and the cumulative number of
authenticated requests recorded for that cache.

### Resolve a catalogue-only manufacturer safely

If inspection shows one manufacturer that is not a real MobileAPI manufacturer
(for example, an internal demo brand), acknowledge that exact name explicitly
in offline mode:

```powershell
Remove-Item Env:MOBILEAPI_API_KEY -ErrorAction SilentlyContinue
py .\scripts\backfill_product_images.py --offline-cache-only --manufacturer "Dashboard Demo" --acknowledge-unavailable-manufacturer "Dashboard Demo"
```

This performs no MobileAPI request and no database write. It reuses all complete
manufacturer pages and verified image matches, records the named manufacturer's
products as `manufacturer_unavailable`, and makes them ineligible for image
updates. The option rejects unknown names and manufacturers whose provider
listing already completed, so it cannot replace valid cached matches. Do not use
it for a transient outage or a genuine MobileAPI manufacturer; resume normally
instead.

Run inspection again and require `last run complete: True` plus zero unresolved
manufacturers before applying:

```powershell
py .\scripts\backfill_product_images.py --inspect-cache
```

`--offline-cache-only` fails closed if any unacknowledged page is missing. New
failed manufacturer fetches store their sanitized failure reason in the cache so
inspection can identify the cause without another paid request.

### Apply the reviewed cache to Neon with zero MobileAPI requests

Remove the API key from the terminal to make the zero-request property obvious,
then apply. Keep `BACKFILL_DATABASE_URL` exactly the same target used for the
dry run:

```powershell
Remove-Item Env:MOBILEAPI_API_KEY -ErrorAction SilentlyContinue
py .\scripts\backfill_product_images.py --apply --confirm-production
```

The apply path does not construct an HTTP client. Before each update it reloads
and locks the product, then confirms existence, smartphone category, cached
brand/model/release date, public HTTPS verification, and `image_url IS NULL`.
The SQL still changes only `products.image_url`. Any mismatch is rejected.

### Refresh a stale or intentionally invalidated cache

Set `MOBILEAPI_API_KEY` again, then start a new dry run explicitly:

```powershell
$env:MOBILEAPI_API_KEY = '<your MobileAPI key>'
py .\scripts\backfill_product_images.py --refresh-cache --request-delay 1 --max-api-requests 1000 --verification-workers 8
```

Refresh never silently reuses the old data. It atomically renames the old cache
to a timestamped `.stale-*` sibling for inspection/recovery, then creates a new
cache as the run progresses. Remove archived cache directories manually only
after review; they contain no credentials.

Any non-loopback database host is treated as remote. A remote write is rejected
unless both flags are present; the database name is not used as a safety check.
SSL options in `BACKFILL_DATABASE_URL` are passed directly to psycopg.

## Expected output and repeat runs

Progress reports the current manufacturer and page, API versus cache reuse,
authenticated requests consumed, manufacturers completed/remaining, and final
matched/verified/skipped/ambiguous/failed counts. Per-product `Ready:` output is
off by default; add `--verbose` when troubleshooting. Anonymous image checks use
eight workers by default and can be bounded from 1 to 16 with
`--verification-workers`; MobileAPI requests remain sequential and rate-limited.

Ambiguous or unreliable matches are never written. A later run re-queries only
rows whose image is still null, and every update repeats the same guarded SQL,
so completed products are not overwritten. Successful updates commit one row at
a time, so an interrupted apply can resume without replacing earlier results.

Tests use fakes only; they make no API or database connections:

```powershell
py -m unittest discover -s scripts\tests -p 'test_*.py' -v
```
