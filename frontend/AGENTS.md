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
  {/* modals, <ScanOverlay>, <ToastStack> rendered last, inside AppShell */}
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
| Form layout | `FormGrid` (2 columns → 1 on phones) and `Field` (label + control, `span2` for full width) |
| Form controls | `Input`, `Select` (pass `options`), `Textarea`, `Range` |
| Feedback after an action | `useToasts()` + `ToastStack` |

### `components/layout/` — app frame

`AppShell`, `ThemeRoot`, `Sidebar`, `Topbar`, `NotificationButton`,
`ProfileCard`, `BrandMark`, and `theme.ts` (the daisyUI theme variables).

### `components/auth/` — accounts

`AuthCard` (sign-up / log-in tabs, used by the Login page) and `SignInModal`
(quick sign-in from inside a page).

### `components/devices/` — device domain

`DeviceGrid` (picks `PhoneCard` for phones, `DeviceCard` otherwise),
`DeviceCard` (`compact` for dashboard use; `children` slot for extra panels),
`PhoneCard` (`DeviceCard` + `PhoneSpecsDropdown`), `DeviceThumb`,
`ConditionBadge`, `UpgradeProfileSummary`, `DeviceFormModal`,
`UpgradePreferencesModal`, `BudgetSlider`, `PriorityPicker`,
`DevicePickerModal`, `ScanOverlay`. Shared types are in `types.ts`, and option
lists and formatters (`money`, `formatDate`, `ageLabel`) are in
`deviceOptions.ts`.

**Getting data onto a card:** components only take the UI `Device` type. Turn
API/database data into it with `deviceFromApi(response, extras)` in
`deviceApi.ts`. `response` is the `GET /api/devices` item (`DeviceResponse`),
and `extras` supplies the product category, the catalogue `phone` row
(snake_case or camelCase), and the preferences. User `spec_overrides` are
applied on top of the catalogue specs. Unknown values stay empty and cards hide
them. Never show placeholder numbers as if they were real.

**Talking to the backend:** all HTTP goes through `src/api/`.
- `client.ts` has `apiFetch`, which adds the bearer token and turns backend
  error bodies into an `ApiError` with a readable message.
- `auth.ts` has `signIn` / `signOut`.
- `devices.ts` has `listDevices` / `createDevice`.
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

---

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
  icons), **1100px** (3-column grids drop to 2). Write them as
  `max-[620px]:`, `max-[860px]:`, `max-[1100px]:`.
- Grid gaps: 14px between cards, 16px between larger sections.
- Card padding: 18px. Modal header/body padding: 20px.

---

## 5. Why sizes are written in px, and other gotchas

`src/index.css` (left over from the Vite template) contains **unlayered** CSS.
Unlayered rules beat Tailwind's layered utilities no matter how specific the
Tailwind class is. It currently sets:

- `:root { font: 18px/145% … }`, so `1rem` = 18px (16px under 1024px). Any
  Tailwind size in `rem` (`p-4`, `text-sm`, daisyUI sizes) renders about 12%
  larger than in the prototype. **Use explicit px values** (`text-[13px]`,
  `p-[18px]`) wherever the size matters.
- `h1` / `h2` font size, weight, colour and margin. Only `PageHeader` renders an
  `h1`, and it uses Tailwind's `!` important modifier to override. Elsewhere use
  `h3` / `h4`, which are not styled there.
- `#root { width: 1126px; text-align: center; … }`. `AppShell` is
  `fixed inset-0` to escape it.

If someone cleans up `index.css`, these workarounds can be simplified. Do that
as one deliberate change across all components, not piecemeal.

---

## 6. Interaction and copy conventions

- **Sentence case** for everything: headings, buttons and labels ("Add device",
  not "Add Device").
- **Money** always goes through `money()` → `S$1,000`. Do not format currency
  inline.
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

- Built to this system: `DevicesPageTest`. Signed out, it shows demo data.
  Signed in, it loads devices from `GET /api/devices` and saves new ones with
  `POST /api/devices`. Edit, remove and upgrade preferences are still
  local-only, and the page says so in its toasts. `PUT` / `DELETE
  /api/devices/{id}` exist but aren't wired yet, and there is no endpoint for
  device preferences.
- Device type isn't stored for devices without a catalogue product (the
  backend derives it from `products.category`, and there's no product-search
  endpoint to link one). After a reload such devices show as type "Other".
- JWT storage is **temporary**: `sessionStorage` via `src/api/session.ts`.
  The token-storage decision is still open. Change `session.ts` when it's
  decided.
- `pages/Login.tsx` (`/login`, `/login?mode=signup`) is built to this system.
  Sign up calls `POST /api/auth/register`, then logs in. Log in calls
  `POST /api/auth/login`. Both then go to `/DevicesPageTest`. The backend
  stores only email + password, so there is no name field.
- **Not yet migrated:** `IngestionAdmin.tsx` (uses its own
  `IngestionAdmin.css`), `pages/ThingieMagiggie.tsx`. When
  you touch one of these, move it onto the shared components.
- `DeviceResponse` does not yet include the product category, the `phone`
  specs or the device preferences, and there is no endpoint for them. Until
  the backend adds them, pass them to `deviceFromApi` via `extras`. When the
  backend adds them, update `DeviceResponse` / `deviceFromApi` in one place.
- Priority and urgency option lists in `devices/deviceOptions.ts` are the
  prototype's display labels. The authoritative preference vocabulary is
  `ai/app/factors.py` (root `AGENTS.md`). Map labels to it when wiring to the
  API; do not change the vocabulary from the frontend.

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
