# Database Design (From Confluence)

# Database Design — Core 12-Table Schema

This document defines the initial database design for the smartphone-focused Tech Advisor. It covers the 12 core tables required to support user accounts, owned-device tracking, **device-specific upgrade preferences**, smartphone product data, changing market information, RAG-based review retrieval, personalised upgrade recommendations, and ingestion observability.

**Scope:** This remains the core 12-table design targeted for the current implementation phase. Personalisation has been revised so upgrade preferences belong to an owned device rather than to the user globally. The previous `user_profiles` table is therefore replaced by `device_preferences`, keeping the core schema at 12 tables. Account-wide settings such as notifications can be added later when those features are implemented.

## Design goals

- Keep the Sprint 1 schema small enough to implement and understand.
- Support smartphones first without prematurely modelling GPU or desktop-specific data.
- Make the affected owned device, its current condition/use, its device-specific upgrade budget and priorities, candidate product data, price and benchmark differences, and retrieved review evidence available to the AI assessment.
- Use structured columns for facts that the backend must calculate or compare deterministically.
- Use JSONB only where the shape is flexible, such as per-device priority weights, use cases, pain points, input snapshots, and factor analysis.
- Keep RAG evidence traceable back to the original source document.
- Preserve enough recommendation context to explain later why a recommendation was generated.

## Entity-Relationship Model

The relationship model below reflects the revised 12-table core schema. The key change is `users → user_devices → device_preferences`: a user may own several devices, and each owned device can have its own upgrade profile.

## 1. User Account & Personalisation

### `users`

The core account and identity table. One row represents one Tech Advisor account. Other tables reference the user's internal ID rather than the email address.

| Column | Purpose |
| --- | --- |
| `id` | Primary key. Unique internal identifier for the user. |
| `email` | Unique login email for the account. |
| `password_hash` | Hashed password value. The plaintext password must never be stored. |
| `role` | Authorization role such as `USER` or `ADMIN`. |
| `enabled` | Controls whether the account can currently be used without deleting the row. |
| `created_at` | Timestamp recording when the account was created. |
| `updated_at` | Timestamp recording the most recent account update. |

**Relationship:** one user may own many `user_devices`. Personalisation used for upgrade decisions is attached to each owned device through `device_preferences`, rather than stored once globally for the whole user.

### `device_preferences`

Stores the upgrade preferences for one specific owned device. This is one of the main personalisation inputs passed into the AI assessment. A user can therefore have different budgets and priorities for different devices instead of one global preference profile.

| Column | Purpose |
| --- | --- |
| `user_device_id` | Primary key and foreign key to `user_devices.id`. Enforces at most one active upgrade-preference profile per owned device. |
| `budget` | Maximum or preferred amount the user is willing to spend to replace this specific device. |
| `currency` | Currency for this device's budget, for example `SGD`. |
| `upgrade_urgency` | How willing the user is to replace this device, for example only when worthwhile, when the device struggles, or as soon as a strong upgrade appears. |
| `brand_flexibility` | Whether the replacement should stay with the same brand/ecosystem or may consider alternatives. |
| `priorities` | JSONB containing factor weights or ordered priorities relevant to replacing this device, such as battery, camera, performance, portability, display, longevity, and value. |
| `pain_points` | JSONB or structured text describing the problems that would motivate replacing this particular device. |
| `notes` | Optional free-form constraints such as avoiding launch-day pricing or requiring a minimum expected lifespan. |
| `created_at` | When this device-specific upgrade profile was created. |
| `updated_at` | When this device-specific upgrade profile was last changed. |

Example `priorities` for one owned smartphone:

```json
{
  "battery": 5,
  "camera": 4,
  "longevity": 4,
  "performance": 2,
  "value": 5
}
```

## 2. User Inventory & Device Context

### `user_devices`

Represents the smartphone devices owned by a user. The recommendation engine uses the relevant owned device as the baseline against which a candidate smartphone is compared. Device facts such as purchase date, condition, satisfaction, and use cases stay with the owned device; upgrade intent stays in its linked `device_preferences` row.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the owned-device record. |
| `user_id` | Foreign key to `users.id`; identifies the owner. |
| `product_id` | Optional foreign key to `products.id` when the user's phone exists in the catalogue. |
| `custom_name` | Manual device name used when the phone is not present in the catalogue. |
| `purchase_date` | When the user obtained the device. Useful for device-age and replacement-context calculations. |
| `condition` | User-reported physical/operational condition such as excellent, good, fair, or poor. |
| `satisfaction_score` | User-reported satisfaction with the current device, for example 0–100. A highly satisfied user may rationally receive a HOLD recommendation even when a newer product exists. |
| `use_cases` | JSONB describing how this particular device is used, such as photography, gaming, study, development, or general use. |
| `is_current` | Indicates whether this is an actively owned/current device that may be considered for upgrade reassessment. |
| `spec_overrides` | Optional JSONB containing user-specific differences from the catalogue entry, such as a different storage capacity. |
| `created_at` | When the device was added to the user's inventory. |
| `updated_at` | When the inventory record was last modified. |

A catalogue-linked device uses `products` and `smartphone_specs` as its technical baseline. `spec_overrides` prevents the system from changing the global catalogue merely because one user's exact configuration differs. Its one-to-one `device_preferences` row then answers a different question: *what would make replacing this owned device worthwhile for this user?*

## 3. Product Catalogue

### `products`

The master list of products known to Tech Advisor. For the current scope, these records represent smartphone models. The generic table name is retained so the project can later support other categories without redesigning the core identity table.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the product. |
| `brand` | Manufacturer, for example Apple, Samsung, or Google. |
| `model_name` | Human-readable smartphone model name. |
| `category` | Product category. Initially this will normally be `SMARTPHONE`. |
| `release_date` | Date the product was released. This can later support product-age and evidence-maturity logic. |
| `status` | Catalogue state such as verified, pending, or archived. |
| `created_at` | When the product was added to the catalogue. |
| `updated_at` | When the product metadata was last changed. |

### `smartphone_specs`

Stores structured, typed smartphone specifications. These fields are deliberately kept separate from `products` so product identity and category-specific technical data do not become one oversized table.

| Column | Purpose |
| --- | --- |
| `product_id` | Primary key and foreign key to `products.id`. One specification row belongs to one product. |
| `chipset` | Processor/System-on-Chip model. |
| `ram_gb` | RAM capacity in gigabytes. |
| `storage_gb` | Storage capacity in gigabytes. |
| `battery_mah` | Battery capacity in milliamp-hours. |
| `wired_charging_watts` | Maximum wired charging power when known. |
| `wireless_charging_watts` | Maximum wireless charging power when known. |
| `display_size_inches` | Display size in inches. |
| `refresh_rate_hz` | Display refresh rate, for example 60 Hz or 120 Hz. |
| `weight_g` | Device weight in grams. |
| `os` | Operating system/platform information. |
| `software_support_years` | Declared years of software support when available. |

The backend should use structured fields like these to calculate exact differences. For example, a battery capacity change or refresh-rate difference should be calculated deterministically and then provided to the AI as trusted context rather than asking the model to perform basic arithmetic.

## 4. Market Data & Change Detection

### `price_history`

Stores price observations over time instead of only storing a single current price. This allows the system to detect price drops and other pricing changes that may make an upgrade newly relevant.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the price observation. |
| `product_id` | Foreign key identifying the product. |
| `price` | Observed price. |
| `currency` | Currency of the price. |
| `source` | Source from which the price was obtained. |
| `observed_at` | Timestamp recording when the price was observed. |

### `benchmark_results`

Stores measurable performance results for a smartphone. These values provide objective comparison data between the user's current phone and a candidate phone.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the benchmark observation. |
| `product_id` | Foreign key identifying the tested product. |
| `benchmark_name` | Name of the benchmark or measurement. |
| `score` | Numeric benchmark result. |
| `unit` | Unit such as points, FPS, seconds, or hours. |
| `higher_is_better` | Tells the backend whether a larger value represents better performance. |
| `source` | Source of the benchmark result. |
| `observed_at` | When the result was collected. |

`higher_is_better` is useful because not every performance metric behaves the same way. Higher benchmark points are normally better, whereas a lower task-completion time may be better.

### `market_events`

Represents a real-world change detected by Tech Advisor. This provides an explicit trigger that can cause the system to reassess whether a product matters to a particular user's affected owned device and its upgrade profile.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the event. |
| `product_id` | Optional foreign key to the affected product. |
| `event_type` | Type of change, for example product launch, price change, benchmark update, specification change, or support change. |
| `title` | Short human-readable event title. |
| `description` | Optional longer description. |
| `old_value` | Optional JSONB representation of the previous value. |
| `new_value` | Optional JSONB representation of the new value. |
| `source` | Where the event/change was detected. |
| `detected_at` | When Tech Advisor detected the event. |

Example: a price-drop event can preserve both the old and new prices and become the trigger for a new recommendation run.

## 5. RAG & Review Evidence

### `review_documents`

Stores the source-level documents used by the RAG pipeline. One row corresponds to one review, article, discussion, or other external source about a product.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the review document. |
| `product_id` | Foreign key linking the source to the smartphone it discusses. |
| `source_name` | Name of the website/publication/source. |
| `source_url` | Original URL of the source. |
| `title` | Source title when available. |
| `published_at` | When the external content was originally published. |
| `ingested_at` | When Tech Advisor imported the document. |

`published_at` and `ingested_at` are intentionally different. The first represents the age of the evidence; the second represents the ingestion history of our system.

### `review_chunks`

Stores smaller pieces of each review document and their vector embeddings. This is the core pgvector-backed retrieval table used by RAG.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the chunk. |
| `review_document_id` | Foreign key linking the chunk back to its original review document. |
| `chunk_index` | Ordering of the chunk within the source document. |
| `chunk_text` | Actual passage that may be retrieved and supplied to the LLM. |
| `embedding` | pgvector embedding used for semantic similarity search. The final vector dimension depends on the selected embedding model. |
| `created_at` | When the chunk and embedding were created. |

Typical RAG flow:

```
review document
    ↓ split
review chunks
    ↓ embed
pgvector
    ↓ semantic search
relevant chunks
    ↓
LLM assessment
```

Review evidence supports the recommendation rather than being the only source of the recommendation. The system can still compare the affected device's upgrade preferences, current-phone context, candidate phone, prices, specifications, and benchmarks when little review evidence exists.

## 6. Recommendation Engine

### `recommendations`

The main persisted output of Tech Advisor. One row represents the system evaluating one candidate smartphone for one user against one specific owned device and that device's upgrade-preference profile.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the recommendation. |
| `user_id` | Foreign key to the user receiving the recommendation. |
| `current_device_id` | Foreign key to the user's owned/current device used as the comparison baseline. |
| `candidate_product_id` | Foreign key to the smartphone being evaluated as an upgrade. |
| `trigger_event_id` | Optional foreign key to the market event that triggered reassessment. |
| `verdict` | Final recommendation category, such as `NO_MEANINGFUL_CHANGE`, `WORTH_WATCHING`, `WORTH_CONSIDERING`, or `STRONG_UPGRADE_CANDIDATE`. |
| `confidence` | Optional confidence score for the assessment. |
| `input_snapshot` | JSONB snapshot of important inputs used at generation time, including the affected owned device, its device-specific budget/priorities/urgency/pain points, candidate product, current price, calculated deltas, and other relevant context. |
| `factor_analysis` | JSONB breakdown of how each factor affected the assessment and how important it was in the affected device's upgrade profile. |
| `reasoning` | Plain-language explanation shown to the user. |
| `ai_model` | Model identifier used for the AI assessment, useful for evaluation and reproducibility. |
| `prompt_version` | Version of the prompt/template used to generate the assessment. |
| `status` | Lifecycle state such as active, superseded, dismissed, or expired. |
| `created_at` | When the recommendation was generated. |

#### Why keep `input_snapshot`?

Device preferences, prices, product data, and even the user's device state can change after a recommendation is generated. If the recommendation only points to today's live data, the team may not be able to reconstruct why the system produced an older result. The snapshot preserves the exact device-specific context and other important inputs used at that time.

#### Why keep `factor_analysis`?

This makes the recommendation explainable instead of persisting only a final label. For example:

```json
{
  "camera": {
    "priority": 5,
    "impact": "HIGH_POSITIVE"
  },
  "battery": {
    "priority": 5,
    "impact": "MODERATE_POSITIVE"
  },
  "performance": {
    "priority": 2,
    "impact": "HIGH_POSITIVE"
  }
}
```

This allows the UI and evaluation process to show both what changed and why that change matters to this specific owned-device context. It also means the same user can rationally receive different verdicts for different devices.

## 7. Operations & Observability

### `system_log`

Stores application-level execution history for ingestion and background processes. It is intended for job-level observability rather than replacing normal application/server logs.

| Column | Purpose |
| --- | --- |
| `id` | Primary key for the log entry. |
| `component` | Job/component that generated the entry, for example review ingestion, price ingestion, RSS processing, embedding generation, or recommendation scheduling. |
| `status` | Execution result such as success, partial success, or failure. |
| `message` | Human-readable summary of what happened. |
| `metadata` | Optional JSONB containing counts, failure details, durations, or other job-specific diagnostic information. |
| `created_at` | When the event/log entry was recorded. |

Example metadata:

```json
{
  "documents_processed": 47,
  "chunks_created": 391,
  "failed": 2
}
```

## Main relationships

| Relationship | Meaning |
| --- | --- |
| `users` 1 → N `user_devices` | A user can own multiple devices. |
| `user_devices` 1 → 1 `device_preferences` | Each owned device can have its own upgrade budget, priorities, urgency, brand flexibility, and pain points. |
| `products` 1 → N `user_devices` | Many users may own the same catalogue model; the reference is optional for manual devices. |
| `products` 1 → 1 `smartphone_specs` | Each smartphone catalogue product has one structured specification record. |
| `products` 1 → N `price_history` | A product can have many historical price observations. |
| `products` 1 → N `benchmark_results` | A product can have many benchmark observations. |
| `products` 1 → N `market_events` | A product may experience many detected real-world changes. |
| `products` 1 → N `review_documents` | A product can have many external review sources. |
| `review_documents` 1 → N `review_chunks` | Each source document is split into multiple retrievable chunks. |
| `users` 1 → N `recommendations` | A user can receive many recommendations over time. |
| `user_devices` 1 → N `recommendations` | The same owned device may be reassessed multiple times as products, prices, evidence, or market events change. |
| `products` 1 → N `recommendations` | A product may be considered as a candidate for many users/devices. |
| `market_events` 1 → N `recommendations` | A detected change may trigger reassessment for multiple affected owned devices. |

## End-to-end recommendation data flow

```
users
  account / identity context
          +
user_devices
  affected owned phone + condition + satisfaction + use cases
          +
device_preferences
  this device's budget + priorities + urgency + pain points
          +
products + smartphone_specs
  candidate phone and structured differences
          +
price_history + benchmark_results
  calculated price/performance changes
          +
market_events
  what changed and why reassessment occurred
          +
review_chunks + pgvector
  relevant unstructured evidence
          ↓
Personalised AI assessment for this owned device
          ↓
recommendations
```

The intended division of responsibility is:

- **Database:** persists user context, trusted structured product facts, changing market data, RAG evidence, and recommendation history.
- **Spring Boot:** retrieves/validates data and performs exact deterministic calculations such as price and benchmark deltas.
- **FastAPI / AI layer:** receives the affected owned device, that device's upgrade preferences, structured comparison context, and retrieved review evidence, then performs the contextual personalised assessment.
- **pgvector:** retrieves semantically relevant review chunks for RAG.

## Flyway evolution

This 12-table design is the core foundation. Additional tables should be introduced only when their corresponding application features are implemented, rather than creating the entire future schema upfront.
