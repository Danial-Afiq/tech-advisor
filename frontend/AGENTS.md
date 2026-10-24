# AGENTS.md — Frontend visual consistency

This file covers **how the Tech Advisor web UI should look and how to build it
so every page looks the same**. It sits under the root [`/AGENTS.md`](../AGENTS.md),
which is still the source of truth for architecture, API contracts, product
behaviour and workflow. If the two conflict, the root file wins; update both.

The visual reference is the interactive prototype `tech-advisor-prototype-v2.html`
(dark navy UI with purple/cyan accents). The first page built to it is
`src/pages/DevicesPageTest.tsx`. Use that page as the worked example.

---

## 1. The one rule

**Build pages from the components in `src/components/`.** Before writing any
markup with colours, borders, radii or shadows, check whether a component
already does it. If a page needs something new that could appear on a second
page, make it a component first and then use it.

What this means in practice:

- Do not copy class strings from one component into a page. Import the component.
- Do not add per-page `.css` files, `<style>` blocks or `style={{…}}` for
  appearance. The only inline style in the app is the theme variables on
  `AppShell`.
- Do not add another UI library (MUI, Chakra, shadcn, Bootstrap, etc.). The
  stack is **Tailwind CSS v4 + daisyUI v5**, loaded in `src/App.css`.
- Do not add new colours, radii or font sizes ad hoc. Use the tokens in §4. If
  one is genuinely missing, add it to this file and the relevant component in
  the same change.

---

## 2. Page skeleton

Every signed-in page uses the same frame:

```tsx
<AppShell active="devices" title="My devices" user={user} topbarActions={…}>
  <PageHeader eyebrow="…" title="…" description="…" action={<Button …/>} />
  {/* optional <Callout> */}
  {/* page content: grids of <Card>-based components */}
  {/* modals and <ToastStack> rendered last, inside AppShell */}
</AppShell>
```

- **`AppShell`** (`components/layout/AppShell.tsx`) provides the theme, the page
  background, the sidebar, the sticky top bar and the content width
  (`max-w-[1500px]`, 26px padding). Never recreate any of these by hand.
- Set `active` to the page's `NavKey` so the sidebar highlights correctly. New
  top-level pages must be added to `NAV_ITEMS` in `Sidebar.tsx`.
- Render modals, overlays and `ToastStack` **inside** `AppShell` so they inherit
  the theme variables.
- Pages that are not signed-in (login, sign-up, onboarding) do not use the
  sidebar. Wrap them in `ThemeRoot` (the themed full-screen root that
  `AppShell` itself uses) and build them from the same `Card`, `Button` and
  form components. `pages/Login.tsx` is the example.

---

## 3. Component inventory

Reach for these first. All are named exports.

### `components/ui/` — generic

| Need | Use |
|---|---|
| Any button | `Button` with `variant` `primary` / `secondary` / `ghost` / `danger` and `size` `md` / `sm` |
| A content surface / panel | `Card` (wrap content in `<div className="card-body …">`) |
| Page title block | `PageHeader` |
| Small uppercase label above a heading | `Eyebrow` |
| Explanatory note / hint box | `Callout` |
| Nothing to show yet | `EmptyState` (icon, title, description, action) |
| Percentage bar | `Meter` |
| Small pill label | `Tag` |
| Dialog | `Modal` + `ModalActions` for the footer buttons |
| Form layout | `FormGrid` (2 columns → 1 on phones) and `Field` (label + control, `span2` for full width; pass `htmlFor` when the field holds more than the control, e.g. a suggestion list) |
| Muted note under a control | `FieldHint` |
| Form controls | `Input`, `Select` (pass string options or `{ value, label }` options), labelled square `Checkbox`, `Textarea`, `Range` |
| Feedback after an action | `useToasts()` + `ToastStack` |

### `components/layout/` — app frame

`AppShell`, `AdminShell`, `ThemeRoot`, `Sidebar`, `Topbar`, `NotificationButton`,
`ProfileCard`, `BrandMark`, and `theme.ts` (the daisyUI theme variables).

### `components/auth/` — accounts

`AuthCard` (sign-up / log-in tabs, used by the Login page) and `SessionRoute`
(route guards). Signed-in pages never offer an in-page sign-in.

### `components/devices/` — device domain

`DeviceGrid` (picks `PhoneCard` for phones, `DeviceCard` otherwise),
`DeviceCard` (`children` slot for extra panels; nudges "set a budget" when the
device has none, because the backend never evaluates it without one),
`PhoneCard` (`DeviceCard` + `PhoneSpecsDropdown`), `DeviceThumb` (product
image, else the type emoji; also used for suggested products on dashboard cards),
`ConditionBadge`, `UpgradeProfileSummary`, `DeviceFormModal`,
`UpgradePreferencesModal`, `BudgetSlider`, `PriorityPicker`,
`CatalogueSearchInput` (ARIA combobox of
catalogue suggestions; typing stays free text), `PhoneSpecFields` (editable
phone specs, tagging fields changed from the catalogue as "Edited").
`useSmartphoneCatalogue(enabled)` loads the catalogue once, and
`catalogue.ts` holds the matching, spec-form and `withCatalogue` helpers. Shared types are in `types.ts`, and option
lists and formatters (`money`, `formatDate`, `ageLabel`) are in
`deviceOptions.ts`.

**Getting data onto a card:** components only take the UI `Device` type. Turn
API/database data into it with `deviceFromApi(response, extras)` in
`deviceApi.ts`. `response` is the `GET /api/devices` item (`DeviceResponse`),
and `extras` supplies the product category, the catalogue `phone` row
(snake_case or camelCase). All preferences come from the response. User `spec_overrides` are
applied on top of the catalogue specs. Unknown values stay empty and cards hide
them. Never show placeholder numbers as if they were real.

**Talking to the backend:** all HTTP goes through `src/api/`.
- `notifications.ts` has the in-app notification calls (see
  `components/notifications/` below).
- `client.ts` has `apiFetch`, which adds the bearer token and turns backend
  error bodies into an `ApiError` with a readable message.
- `auth.ts` has `signIn` / `signOut`.
- `devices.ts` has `listDevices` / `createDevice` / `updateDevice` / `deleteDevice`.
- `catalogue.ts` has `listSmartphones` (`GET /api/catalogue/smartphones`) and the
  ADMIN `createSmartphone` / `updateSmartphone` / `deleteSmartphone`
  (`/api/admin/catalogue/smartphones[/{id}]`), plus the read-only
  `listSmartphonePrices` / `listSmartphoneBenchmarks` (`.../{id}/prices`,
  `.../{id}/benchmarks`).
- `database.ts` has `listDatabaseTables` (`GET /api/admin/database/tables`).
- `session.ts` stores the JWT.

Components never call `fetch` directly. Write the payload with
`deviceToRequest(device)`. Show async failures inside the form with
`FormError`, and use `Button`'s `loading` prop while a request runs.
`DeviceFormModal`'s `onSave` may return a promise, and a rejection keeps the
modal open with the error shown.

**Phone specifications:** `phoneSpecs.ts` mirrors the `phone` table. To show a
new spec, add the column to `PhoneSpecs` and a row to `PHONE_SPEC_GROUPS`
(label, unit or formatter). The dropdown picks it up automatically.

When you add a new domain (for example recommendations), create
`components/recommendations/` following the same pattern: types file, options
and helpers file, then components.

### `components/recommendations/` — recommendation domain

`RecommendationCard` presents one persisted recommendation in the standard
dashboard format: owned device, suggested product, Indicative price, deterministic verdict,
owner evidence grade and reasoning. `showDevice={false}` drops the owned-device
heading (the dashboard already groups cards under it) and makes the suggested
product the title; `children` adds panels under the price. `SpecComparison` is
the "Compare specs" button and its side-by-side spec table in a `Modal` (`compareSpecs.ts` builds its rows from
`PHONE_SPEC_GROUPS`, so a new phone spec shows up there too), and
`useComparisonSpecs()` supplies both sides from the device and catalogue APIs.
`groupRecommendationsByDevice` and `TOP_RECOMMENDATIONS_PER_DEVICE` in
`recommendationOptions.ts` drive the per-device top picks (root §23.4). `recommendationPresentation.ts` owns the
readable labels, themed badge styles and attention behaviour for the four
canonical verdicts, plus stored-currency-aware indicative-price formatting. `types.ts`
defines the API-aligned recommendation, verdict
and evidence-grade types. Keep verdict and evidence grade visually separate;
display `-` as "Not enough evidence yet" and `null` as no available evidence
grade. The dashboard renders the pricing limitation once near the recommendation
controls, never once per card: "Prices are indicative and may vary by storage
configuration, retailer, region and availability."

`RecommendationFilterDropdown` supplies the dashboard's owned-device select,
verdict checkboxes and evidence-grade checkboxes. `types.ts` defines filter
state; `recommendationOptions.ts` holds option lists, device options and the
AND/OR matching helper. Devices come from the full active recommendation list
and use IDs, so identical display names stay separate. The dropdown uses the
shared `Button` (including a trigger ref for focus return), `Card`, `Field`,
`Select`, `Checkbox` and `FieldHint`. `EmptyState` titles are semantic headings.
The dashboard API module owns the four sort options and URL parsing; sorting is
server-side, then `filterRecommendations` narrows the returned order without
re-sorting it. The card's star toggle (`onToggleStar`, `starPending`) is shown
only when the page handles it; the page owns confirming an unstar. The Saved
toolbar toggle is `savedOnly` in the same filter state; `selectedFilterCount`
and the dropdown's Clear filters deliberately ignore it (root §26.4).
When `onDismiss` is supplied, the card also shows **Not interested**. The
dashboard owns the reason/comment modal, calls the dismissal API, and removes
the card only after the request succeeds (root §26.4).

---

### `components/notifications/` — in-app notifications (ticket 4.4.2)

`NotificationCenter` is the self-contained top-bar bell: it wraps the shared
`NotificationButton` (which accepts `ref`/ARIA props and caps the badge at
99+), loads the unread count on mount and re-checks it every 45s
(`NOTIFICATION_POLL_MS`), and opens a dropdown built from `Card`, `FormError`,
`LoadingBlock`, `EmptyState` and `Button`. Each notification is one clickable
row: phone icon, backend-written title and message, relative time
(`relativeTime`: "Just now", "5m ago", …) with Read/Unread, and a blue dot
while unread. Clicking it marks it read and navigates to
`/dashboard?recommendation={id}`, which outlines and scrolls to that card. A
notification whose recommendation is gone (`available: false`) says so and
does not navigate. Each row has an × (styled like the `Modal` close button) that
permanently deletes that notification. The panel also offers Mark all as read,
Clear all (asks for confirmation inline first, because it deletes everything) and
View older notifications (next page). Render it on any signed-in USER page as
`topbarActions={<NotificationCenter />}`.

**Backend contract** (`src/api/notifications.ts`, types in
`components/notifications/types.ts`; implemented by `notification/` in the
backend, root `AGENTS.md` §14.14):

| Route | Returns |
|---|---|
| `GET /api/notifications?page&size` | `{ items, page, size, totalItems, totalPages }`, newest first |
| `GET /api/notifications/unread-count` | `{ count }` |
| `PUT /api/notifications/{id}/read` | 204 (another user's id: 404) |
| `PUT /api/notifications/read-all` | 204 |
| `DELETE /api/notifications/{id}` | 204; deletes the row (another user's id: 404) |
| `DELETE /api/notifications` | 204; deletes all of the user's notifications |

The backend keeps only each user's newest 10 notifications (configurable), so the
list rarely needs a second page.

Item fields: `notificationId`, `type` (`NEW_RECOMMENDATION`), `title`,
`message`, `recommendationId` (the best recommendation in the group; null once
deleted), `available`, `read`, `createdAt`. The text is written by the backend;
the frontend never builds it.

**Gotcha — 401 for routes that don't exist.** The backend answers *any*
unknown route with 401, even with a valid token (its `/error` page is not in
the security permit list). `apiFetch` signs the user out on 401, so every
notification call passes `keepSessionOn401: true` and treats 401/404/405 as
"unavailable" (`isNotificationsUnavailable`): the bell then shows no badge and
the panel says notifications aren't available yet. This matters whenever the
frontend is deployed ahead of the backend. Keep the option on any call to a
route that may not be deployed yet.

## 4. Design tokens

These are the only values that should appear in class names. They come from
the prototype and match `layout/theme.ts`.

### Colour

| Role | Value | daisyUI name (inside `AppShell`) |
|---|---|---|
| Page background | `#08101f` + two radial glows (in `AppShell`) | — |
| Card surface | gradient `rgba(17,29,49,.92)` → `rgba(12,22,38,.92)` (in `Card`) | — |
| Modal surface | `#0d1727` | `base-100` |
| Inset panel / field background | `#0a1423` (panels), `#091321` (inputs) | `base-200` (approx.) |
| Raised / hover surface | `#111d31`, `#16243b` | `secondary`, `base-300` |
| Border | `white/[0.09]` | — |
| Text | `#eef5ff` | `base-content` |
| Muted text | `#8fa0b8` | — |
| Label text | `#c6d1df` | — |
| Eyebrow text | `#9fb1c9` | — |
| Primary accent | `#7c5cff` (buttons use gradient to `#5f85ff`) | `primary` |
| Secondary accent | `#28c0ff` (used in gradients with purple) | `accent`, `info` |
| Good | `#2ed39a` | `success` |
| Warning | `#f6b84b` | `warning` |
| Bad / destructive | `#ff6d7a` | `error` |

For status colours prefer daisyUI modifiers (`badge-success`, `badge-soft`,
`progress-primary`, …) over hex values, so they follow the theme.

### Shape

| Element | Radius |
|---|---|
| Card | 18px |
| Modal | 22px |
| Inset panel, list row | 13–14px |
| Input, select, md button | 12px |
| sm button | 10px |
| Pills, tags, badges | full |
| Checkbox | 0px (square) |

Borders are 1px `white/[0.09]` almost everywhere. Card shadow is
`0_12px_34px_rgba(0,0,0,.14)`; modal and toast shadow is
`0_24px_70px_rgba(0,0,0,.33)`.

### Type scale (px)

| Use | Size / weight |
|---|---|
| Page title (`PageHeader`) | 30 bold, `-0.035em`, 25 on phones |
| Modal title | ~19 bold |
| Card title | 16 extrabold |
| Body / description | 16 (page), 13 (modal copy) |
| Labels | 13 semibold |
| Meta / secondary text | 12 |
| Tags, meters, fine print | 10–11 |
| Eyebrow | 12 bold uppercase, `.12em` tracking |

### Layout

- Breakpoints are the prototype's, not Tailwind's defaults: **620px** (phone:
  single column, hide top-bar title), **860px** (sidebar collapses to 78px
  icons with a 10px caption under each, so touch users can still read them), **1100px** (3-column grids drop to 2). Write them as
  `max-[620px]:`, `max-[860px]:`, `max-[1100px]:`.
- Grid gaps: 14px between cards, 16px between larger sections.
- Card padding: 18px. Modal header/body padding: 20px.

---

## 5. Global CSS

The only global stylesheet is `src/App.css` (Tailwind + daisyUI). The Vite
template's `index.css` was removed (`feat/declutter-device-ui`), so there are no
unlayered rules to fight: `1rem` is the browser's 16px, headings are reset by
Tailwind's preflight, and `PageHeader` needs no `!` modifiers. Do not add a new
global stylesheet; style through components. Existing components still use
explicit px values to match the prototype, and new ones should too.
`ThemeRoot` stays `fixed inset-0` because it is the scroll container that the
sticky sidebar and top bar stick to.

---

## 6. Interaction and copy conventions

- **Sentence case** for everything: headings, buttons and labels ("Add device",
  not "Add Device").
- **Money** always uses a domain formatter, never inline formatting:
  - Device budgets use `money(amount, currency?)` → `S$1,000` (SGD or unset) or `USD 1,000`.
  - `budgetLabel(prefs)` shows "No budget set" when no device budget exists.
  - Recommendation API prices use `formatRecommendationPrice()`; the backend
    returns the selected display currency plus conversion freshness metadata.
  - The shared top-bar selector offers SGD, USD, MYR and GBP, persists
    `tech-advisor-currency` in localStorage and defaults to SGD. Price conversion
    is display-only; never relabel a backend `UNAVAILABLE` original amount.
- **Dates** go through `formatDate()`.
- Buttons: one `primary` action per area; `ghost` for cancel/back/secondary
  navigation; `danger` only for destructive actions. Icon glyphs in labels use
  the prototype's set (`＋`, `✦`, `→`, `←`, `×`).
- Every `<button>` has an explicit `type` (the `Button` component defaults to
  `"button"`). Icon-only buttons need `aria-label`; toggle buttons use
  `aria-pressed`.
- Confirm success and failure with a toast (`pushToast(title, text, icon)`),
  not `alert()`.
- Destructive actions ask for confirmation first.
- Empty lists show `EmptyState` with a call-to-action, never a blank area.
- Multi-step flows use successive modals (see add device → "Step 2 · upgrade
  profile"), with the `Eyebrow` showing the step.
- Upgrade preferences are **per device** (root `AGENTS.md` §26.1). Never design
  a global preference screen.

---

## 7. Current state (keep this section up to date)

- Built to this system: `DevicesPageTest`, mounted at the authenticated USER
  route `/devices`. It loads devices from `GET /api/devices`, saves new ones
  with `POST /api/devices`, edits with `PUT /api/devices/{id}` and removes
  with `DELETE /api/devices/{id}` (a failed remove keeps the device and shows
  a toast; a 404 counts as already removed). Saving the upgrade-profile
  modal sends all seven preference fields with
  `PUT /api/devices/{id}` (via `deviceToRequest`), and a rejection keeps the
  modal open with the error. `GET /api/devices` returns them, so the card
  restores the complete profile after a reload, or "No budget set". The add-device
  form sends `budget: null`; a device with no saved budget starts the slider
  at `SUGGESTED_BUDGET`. There is no currency picker: an existing currency is
  kept, otherwise SGD is sent. Priorities use the twelve canonical factor keys;
  the adapter preserves saved weights and gives newly selected priorities weight 1.
  Urgency/brand selects use canonical API values with readable labels. Pain points
  and notes persist too; existing JSON pain-point arrays are preserved until edited.
  Older budget-only profiles may return the JSONB pain-point default `{}`; the
  adapter sends JSON-encoded empty text for that value (and JSON null), while
  preserving supported unchanged string/array values.
  Brand is required only when adding a
  device: a saved device without a catalogue link comes back with its whole
  name in the model field.
- `pages/DashboardPage.tsx` is the authenticated USER `/dashboard` route. It
  loads only the user's active recommendations from
  `GET /api/dashboard/recommendations`. It is the only recommendation-browsing
  page: the duplicate Recommendations sidebar item is removed and the protected
  `/recommendations` compatibility path redirects to `/dashboard`. The Filters
  dropdown narrows the list
  immediately by one device and any number of verdicts/evidence grades, with
  AND between groups and OR within each group. Empty selections show all results;
  a no-match selection shows `EmptyState` with Clear filters. `confidence`
  is labelled Evidence grade and offers only A–F as square checkboxes.
  Insufficient evidence (`-`) and unavailable (`null`) recommendations remain
  visible when no grade is selected. Most relevant is the default server-side
  sort; price ascending, price descending and newest-first are also available.
  Sort is URL-backed, survives refreshes and does not reset the local filters;
  filters preserve the server order. The result count, Filters and Sort controls
  share one responsive toolbar. Recommendation cards label the representative
  model-level amount "Indicative price", preserve "Price unavailable", and the
  toolbar displays the pricing disclaimer once per populated page. Overview
  cards retain totals for all active recommendations. Users
  star a card to save it (persisted), see saved ones with the Saved (n)
  toolbar toggle, and confirm in a `Modal` before unstarring. See root §23.4,
  §26.4 and §27.9. A `?recommendation={id}` query (set when a notification
  is opened) outlines and scrolls to that card, or explains with a `Callout`
  that it is no longer active or is hidden by the current filters.
  Cards are grouped per owned device and show that device's top 3, with a
  per-device Show all; each card has a Compare specs button that opens a dialog (root §23.4).
  Paragraphs placed directly in a `card-body` need `grow-0`: daisyUI gives
  `.card-body p` `flex-grow: 1`, which spreads them down a stretched card.
  The three overview cards stay three across on phones instead of stacking.
- The user sidebar offers only Dashboard and My devices. The placeholder
  Activity and Settings items (no pages behind them) were removed. Device cards show the
  Satisfaction meter only; the old "Context fit" meter (a clamped copy of
  satisfaction) and the never-wired "Simulate update" button were removed,
  along with `ScanOverlay`, `DevicePickerModal`, `DeviceCard`'s unused
  `compact` mode, and `/devices`' signed-out demo mode (`demoData.ts`,
  `SignInModal`), which the USER route guard made unreachable.
- Each dashboard card shows the suggested phone's photo (the phone emoji when
  the catalogue has none or it fails to load), a one-line summary of the biggest spec changes ("Battery 54%
  better · RAM 33% better") and, in the Compare specs dialog, an arrow for the
  direction the value moved, with the suggested value and arrow green when
  better or red when worse (differing text specs are only bolded). Both
  come from the response's `specChanges`, which the backend direction-corrects
  with the verdict's own rules; the UI never decides better or worse itself. The page description explains that the verdict and the owner
  evidence grade are separate.
- **In-app notifications (ticket 4.4.2).** The top-bar bell on `/dashboard`
  and `/devices` is `NotificationCenter` (replacing the dashboard's attention
  count and the devices page's hard-coded `1`). See
  `components/notifications/` above and root §14.14.
- Signed in, the device form suggests smartphones from the catalogue as the
  user types a model name. Picking one sends its `productId` and prefills the
  spec fields. Only specs the user changes are saved as `specOverrides`,
  keyed by snake_case `phone` column name (what the backend reads). Clearing
  a prefilled field saves it as an explicit `null` override, so it stays
  blank after a reload. The backend's comparison treats a `null` override as
  unknown (skipped, not scored), never as the catalogue value. Emptying a
  prefilled field shows a warning with "Use catalogue value" and "Keep
  empty"; the form won't save until each emptied field has one of them.
  Specs already saved as cleared count as kept. No match, an unavailable catalogue, or "Enter manually
  instead" all leave plain manual entry, with no error. Matching is
  client-side over the full list.
- `GET /api/devices` doesn't return specs, so the page fills linked devices'
  specs, and their product image (`imageUrl`, MobileAPI's thumbnail), from the
  catalogue list with `withCatalogue`. `DeviceThumb` shows that image instead of
  the type emoji and falls back to the emoji if it fails to load. The catalogue only
  has smartphones. It has no GPU/VRAM or launch-price columns, so nothing
  prefills those.
- Device type isn't stored for devices without a catalogue product. Linked
  devices take it from `DeviceResponse.productCategory`, so they open as
  "Phone" in Edit even before the catalogue loads. After a reload, unlinked
  devices show as type "Other" (a real option in the type select, so Edit no
  longer silently switches them to "Desktop PC"), or "Phone" if they have spec
  overrides.
- JWT storage is **temporary**: `sessionStorage` via `src/api/session.ts`.
  The token-storage decision is still open. Change `session.ts` when it's
  decided.
- `components/auth/SessionRoute.tsx` applies route-level session/role guards.
  `/` and `/login` redirect an existing session to `/dashboard` for USER or
  `/admin/ingestion` for ADMIN. The two protected routes redirect signed-out
  visitors to `/login` and redirect the wrong role to its own home route.
  `/DevicesPageTest`, `/IngestionAdmin` and USER `/recommendations` remain
  compatibility redirects.
- `pages/Login.tsx` (`/login`, `/login?mode=signup`) is built to this system.
  Sign up calls `POST /api/auth/register`, then logs in. Log in calls
  `POST /api/auth/login` for USER and ADMIN accounts, then routes by the returned
  role. The backend stores only email + password, so there is no name field.
- `IngestionAdmin.tsx` uses the shared UI components and the application's ADMIN
  session only. It has no ingestion-specific password gate. SearchAPI review
  ingestion adds a validated product picker to the same authenticated page. The
  phone field uses `CatalogueSearchInput` to resolve partial text to an exact
  verified catalogue name before provider discovery. Lookup/run errors are kept
  separate from the two-second status poll, so a successful poll cannot erase an
  action error before the administrator can read it.
  While TechAPI's initial backfill is running, the page shows its durable
  processed/total progress, stored observations and covered products. That
  progress card is hidden after the source enters incremental monitoring;
  fortnightly scheduling and run outcomes remain visible through the normal
  schedule summary and ingestion log.
- `pages/AdminCatalogue.tsx` is the separate ADMIN-only `/admin/catalogue` page.
  It loads the whole catalogue once and searches (brand, model, chipset, OS, ID),
  filters (brand, status, OS family, complete/missing specs, has/no price, and a
  price range whose presets fill editable inclusive min/max bounds), sorts
  (including latest price, unpriced last) and pages it
  client-side (`components/admin/catalogueAdmin.ts`; 10/25/50/100 per page).
  `CatalogueEntryModal` adds or edits an entry (product fields plus
  `PhoneSpecFields` without a baseline; blank specs are sent as `null`), and
  delete asks for confirmation first. Editing also shows
  `CatalogueHistorySection`: read-only price history (current price, variant,
  source) and benchmark results from the ADMIN `.../{id}/prices` and
  `.../{id}/benchmarks` routes. "Show visualisation of all tables" opens
  `DatabaseTablesPanel`, one card per table from `GET /api/admin/database/tables`.
  Catalogue GET routes are available to authenticated USER and ADMIN accounts,
  while mutations remain ADMIN-only.
- Both admin pages use `AdminShell`, which supplies the same responsive sidebar
  treatment as user pages, navigation between manual ingestion and catalogue,
  and the profile-card sign-out control.
- Signed-in USER pages also sign out from the shared profile-card icon; do not
  add a second page-level sign-out button.
- **Not yet migrated:** `pages/ThingieMagiggie.tsx` (`/ThingieMagiggie`), kept
  deliberately. When you touch it, move it onto the shared components.
- `DeviceResponse` includes budget, currency, upgrade urgency, brand flexibility,
  weighted priorities, pain points, notes and the linked product's category.
  `deviceFromApi` always takes these from the response; `extras.preferences`
  has been removed and `extras.category`, if passed, overrides the response's.
  `phone` specs still come from `extras`/`withCatalogue`. When the backend adds
  them, update `DeviceResponse` / `deviceFromApi` in one place.
- `devices/deviceOptions.ts` maps priority labels to the twelve factors and
  defines canonical urgency/brand-flexibility select values. The authoritative
  vocabulary remains `ai/app/factors.py` and its Java mirrors (root `AGENTS.md`).
  Do not introduce unsupported choices from prototype labels.

---

## 8. Checklist before handing off UI work

1. The page is wrapped in `AppShell` (or uses the shared theme if it is a
   signed-out page).
2. No hand-rolled buttons, cards, modals, inputs or toasts where a component
   exists.
3. No new colours, radii or font sizes outside §4, or this file was updated to
   add them.
4. Checked at roughly 1400px, 1000px, 800px and 390px wide.
5. `npm run lint` and `npm run build` pass.
6. If you added or changed a shared component or token, you updated §3/§4 here.
