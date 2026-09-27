# Source Evaluation Template

Fill this out **before** starting implementation against any new external
data source (an ingestion source, a scraped site, a third-party API) —
not after. This is the fix for "late data sourcing": the whole point is to
catch legal, cost, and data-quality problems before a sprint is spent
building against a source that turns out to be blocked, too expensive, or
missing the data the ticket actually needs.

One copy of this file per source, saved as
`docs/source-evaluations/<source-name>.md`, linked from the ticket before
it moves to "In Progress." Once a source is cleared, its summary also gets
folded into `AGENTS.md` §17 (source adapter notes) — this file is the full
record; AGENTS.md carries the short version other engineers actually read
day to day.

Keep every finding evidence-based. "I skimmed the homepage" is not a
finding — quote the actual robots.txt line, link the actual ToS page,
paste the actual response. A source with no finding recorded is not yet
evaluated, no matter how obviously fine it looks.

---

## Source

- **Name:**
- **Base URL(s):**
- **Related ticket(s):**
- **Evaluated by:**
- **Date:**

## 1. Legal

### robots.txt
- **Checked?** Yes / No
- **URL fetched:** `https://<host>/robots.txt`
- **Findings:** (quote the relevant `Disallow`/`Allow` lines; note any
  bot-specific block — e.g. `ClaudeBot`, `anthropic-ai`, `GPTBot` — by name)
- **Paths we need vs. paths allowed:**

### Terms of Service
robots.txt alone is **not sufficient clearance** — a permissive robots.txt
can still sit under a ToS that prohibits AI training or scraping (this is
exactly how TechRadar/Future plc was nearly built against before its ToS
was actually read). Always check both.

- **Checked?** Yes / No
- **URL fetched:** (the actual ToS page, not a search result about it)
- **Owning entity:** (e.g. "Future plc", "SPH Media" — ToS are often
  written at the publisher/parent-company level, not the site level)
- **Findings:** (quote any clause on data mining, scraping, AI
  training/fine-tuning, automated access — verbatim, with no paraphrasing
  that could soften or misstate what it says)

### Verdict
- [ ] **Cleared** — no blocking robots.txt or ToS restriction found for
      the paths we need
- [ ] **Rejected** — blocked by robots.txt and/or ToS (state which, and
      the exact clause)
- [ ] **Needs follow-up** — ambiguous; needs a human decision before any
      code is written against this source

**Rationale (one or two sentences):**

## 2. Cost

- **Pricing model:** Free / Freemium / Paid / Unknown
- **API key or account required?** If yes, who provisions it and where's
  it stored (env var name)?
- **Rate limit / request budget:** (requests per minute/day, or crawl-delay
  from robots.txt) — does it fit inside the shared ingestion budget
  (`SourceContext`'s 10-requests-per-run ceiling), or does this source need
  its own carve-out?
- **Any cost risk if usage grows** (e.g. per-request billing beyond a free
  tier)?

## 3. Data quality — the part most likely to cause late rework

Don't assume the response has what the ticket needs — fetch a real sample
and check. This is what would have caught the RSS-snippet problem (95
characters, no real body text) and the MobileAPI missing-dependency issue
before either cost implementation time.

- **Sample request actually made:** (the exact URL/params, not a
  hypothetical one)
- **Sample response captured:** link or paste a representative excerpt
  (truncate long payloads, but keep enough to judge shape and quality)
- **Does the response contain what the ticket's AC needs?** Yes / No —
  explain (e.g. "full article body text present" vs. "95-char snippet
  only, insufficient")
- **Parsing fragility noted?** (build-tied CSS class names, undocumented
  JSON shape, inconsistent optional fields, pagination quirks, etc.)
- **Freshness / update cadence:** does the source actually update often
  enough for what the ticket needs (e.g. RSS's last-20-items window
  missing infrequent GPU reviews was a real freshness failure)?

## 4. Decision

- [ ] **Cleared to build** — legal, cost, and data quality all check out
- [ ] **Rejected** — not viable; alternative source needed (name it, if
      known)
- [ ] **Needs a decision from [who]** before proceeding

**Summary for AGENTS.md §17 (2-3 sentences, written so another engineer
doesn't have to open this file to get the gist):**

## Sign-off

- **Reviewed by:**
- **Date:**
- **Linked ticket(s):**
