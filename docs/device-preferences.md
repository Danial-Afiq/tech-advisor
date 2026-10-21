# Device preference persistence (SCRUM-38)

The existing `device_preferences` table already contained all required columns.
Previously, the API persisted only budget/currency and the website kept the other
fields in page state. The complete profile now survives saving and reloading.

## API contract

Use the existing authenticated `POST /api/devices` and `PUT /api/devices/{deviceId}`.
The URL identifies the owned device on update. Ownership comes from the bearer
token; callers cannot choose another user in the request body.

The following fields are returned by create/update, `GET /api/devices/{deviceId}`
and `GET /api/devices`. Each is null when no preferences record exists.

| Field | Format and behavior |
| --- | --- |
| `budget` | Nonnegative number, at most 10 integer digits and 2 decimal places; required for the first preferences save. |
| `currency` | Three uppercase letters; defaults to SGD on creation and stays unchanged when omitted on update. No currency conversion is performed. |
| `upgradeUrgency` | `NOT_URGENT`, `SOMEWHAT_URGENT`, `URGENT` or `CRITICAL`. |
| `brandFlexibility` | `EXTREMELY_FLEXIBLE`, `FLEXIBLE`, `SOMEWHAT_FLEXIBLE` or `NOT_FLEXIBLE`. |
| `priorities` | JSON-encoded object mapping the existing recommendation factor keys to 32-bit integer weights. Send `"{}"` to clear. |
| `painPoints` | JSON-encoded string or array of strings. Send `"\"\""` or `"[]"` to clear. |
| `notes` | Optional text; send `""` to clear. |

Omitting a preference field, or supplying null, preserves its stored value.
Once a budget exists, preference fields can be updated without resending it.
The other editable **device** fields still use the existing PUT replacement
behavior; the website sends the current device details alongside its preferences.

Example body:

```json
{
  "productId": null,
  "customName": "My phone",
  "purchaseDate": "2024-02-15",
  "condition": "GOOD",
  "satisfactionScore": 65,
  "useCases": "[\"photography\",\"gaming\"]",
  "specOverrides": "{}",
  "budget": 1234.56,
  "currency": "USD",
  "upgradeUrgency": "URGENT",
  "brandFlexibility": "SOMEWHAT_FLEXIBLE",
  "priorities": "{\"battery\":5,\"camera\":4,\"longevity\":2}",
  "painPoints": "[\"Battery drains quickly\",\"Camera struggles at night\"]",
  "notes": "Keep the replacement for four years."
}
```

Supported priority factors are `battery`, `camera`, `performance`, `display`,
`build_quality`, `thermals`, `software_support`, `connectivity`, `audio`, `value`,
`longevity` and `portability`. Their definitions remain in the existing Java
`Factors` class and Python `factors.py`.

Priority weights must fit a Java 32-bit integer. There is currently no enforced
1–5 scale or nonnegative-weight constraint; the ticket does not specify one.

Older budget-only records have the JSONB pain-point default `{}`. The frontend
normalizes this empty object, or JSON null, to JSON-encoded empty text before
saving. Existing supported strings and arrays remain unchanged until their text
is edited. This keeps those older profiles editable under the current request
validation without a database migration.

## What each change does

| Files | Change and purpose |
| --- | --- |
| `backend/.../dto/DeviceRequest.java` | Adds the five missing fields, validates urgency/flexibility values and documents the request in OpenAPI. Existing budget/currency validation remains. |
| `backend/.../dto/DeviceResponse.java` | Returns all persisted preferences on every device response, including list/reload. |
| `backend/.../service/DeviceService.java` | Replaces budget-only saving with transactional preference upserts, preserves omitted fields and validates JSON shapes, supported factors and integer weights. It continues using the existing ownership/current-device lookup and recommendation event. |
| `frontend/.../devices/deviceApi.ts` | Sends all fields, reads the saved response, converts display labels to factor keys, preserves existing weights and supported pain-point arrays, normalizes legacy empty pain-point JSON, and stops accepting page-local preference overrides. |
| `frontend/.../devices/types.ts` | Carries saved weights and original pain-point JSON so unrelated edits retain their values. |
| `frontend/.../devices/deviceOptions.ts` | Provides all twelve supported priorities and canonical urgency/flexibility values with readable labels. Newly selected priorities receive equal weight 1. |
| `frontend/.../devices/UpgradePreferencesModal.tsx` | Requires urgency and flexibility selections when completing a profile. Existing async error handling keeps failed saves open. |
| `frontend/.../devices/UpgradeProfileSummary.tsx` | Shows readable urgency labels and identifies missing urgency. |
| `frontend/src/pages/DevicesPageTest.tsx` | Uses persisted preferences from the response after saving and updates the success message to describe the complete saved profile. |
| `frontend/.../devices/demoData.ts` | Uses the same response contract and canonical values for demo profiles. |
| Backend and frontend test files | Cover committed persistence, all fields, repeated updates, missing-budget rollback, device/user isolation, missing/removed devices, authentication, validation, reload and preservation of saved weights. Existing fixtures now include the new response fields. |
| Root and frontend `AGENTS.md` | Replace the old budget-only/page-local descriptions with the implemented contract and remove the obsolete claim that API-created devices cannot supply AI preference context. |

The existing `user_device_id` primary/foreign key guarantees at most one
preferences row for an owned device. No migration or new table is required.
The recommendation pipeline already reads these columns, so saving a complete
profile makes it available to analysis through the existing after-commit trigger.
Its other device/candidate requirements still apply.

## Fact check of the pasted change explanation

The explanation matches the implementation, with these corrections and
clarifications:

- The field name is `painPoints`, not `paintPoints`, throughout the code.
- The sentence about request/response types should say "missing preference
  fields"; "pissing preference fields" is a typo.
- The spelling is `brandFlexibility`, and the DTO adds getters and setters.
- `@Pattern` performs validation when the controller uses `@Valid`.
  `@Schema` documents the API and does not perform runtime validation.
- A budget is required when creating the first **preferences record**. A device
  can still be created without preferences and completed later.
- The non-null update rule preserves other **preferences**. The existing device
  PUT still replaces editable device details, so callers send the current device
  fields alongside the preference change.
- All device responses expose the stored preference fields; missing records
  produce null values rather than an automatically completed profile.
- Pain-point arrays are preserved only when they contain supported text values
  and their displayed text is unchanged. Empty legacy objects/null are normalized.
- The initial integration test count was 23. The verified class now has 32 passing
  cases after adding boundary, optional-value, legacy-profile and analysis coverage.

## Acceptance-criteria coverage

| SCRUM-38 requirement | Automated evidence |
| --- | --- |
| Create preferences for the correct owned device | `createPersistsAllFieldsForTheNewOwnedDevice` verifies the device owner, stored device id and isolation from an existing device. |
| Save and retrieve every supported preference field accurately | `updateAndListRoundTripEveryFieldWithoutDuplicates`, `everySupportedFactorUrgencyAndBrandFlexibilityCanBePersisted`, `budgetBoundariesRoundTripWithoutLosingDecimalPrecision` and the optional-notes test verify database and API values. |
| Update without duplicates | Repeated updates retain the creation timestamp and leave exactly one row for the owned-device primary key. Notes-only/null update tests verify preservation. |
| Isolate preferences between devices | `preferencesStayIsolatedBetweenDevicesAndUsers` and the frontend reload test verify independent device values. |
| Reject nonexistent or unauthorized devices | `nonexistentOtherUsersAndRemovedDevicesCannotBeReadOrChanged` checks 404 responses and unchanged private data; `authenticationIsRequiredForReadsAndWrites` checks 401 responses. |
| Pass persistence/access-control tests | The dedicated integration class exercises real authenticated requests and committed PostgreSQL persistence. |

`apiSavedPreferencesAreAvailableToRecommendationAnalysis` additionally builds the
real recommendation assessment request from records saved through the device API
and verifies all seven preference values. It supplies a fixed classification
fixture and makes no external AI call. Frontend regressions cover completing
older budget-only profiles and preserving values after a reload.

## Verification

Verified on 21 October 2026:

- Dedicated SCRUM-38 database integration tests: 32 passed.
- Complete backend suite: 462 passed, 1 skipped, 0 failures or errors across 54 classes.
  The skip is the existing optional live-embedding smoke test, which requires
  `REVIEW_SMOKE_AI_URL`; none of the SCRUM-38 tests were skipped.
- Complete frontend suite: 182 passed across 12 test files.
- Frontend production build, ESLint and `git diff --check`: passed.
- The new legacy-profile regressions failed before the adapter fix and passed
  afterward. Backend verification used a disposable PostgreSQL/pgvector test
  container that was removed after the run.

From `backend/`, using the project's dedicated PostgreSQL database ending `_test`:

```sh
./mvnw -Dtest=DeviceServiceTest,DevicePreferencesIntegrationTest,OpenApiDocumentationTests test
```

`DevicePreferencesIntegrationTest` uses real HTTP security and committed database
writes. Its fixtures use unique users and clean up their own rows through the
existing foreign-key cascades. It never deletes the entire inventory.

From `frontend/`:

```sh
npm test
npm run build
npm run lint
```
