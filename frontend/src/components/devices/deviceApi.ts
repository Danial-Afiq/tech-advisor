import { CONDITIONS, DEFAULT_CURRENCY, PRIORITY_FACTORS, defaultPrefs } from "./deviceOptions";
import { PHONE_SPEC_KEYS, TEXT_SPEC_KEYS } from "./phoneSpecs";
import type { PhoneSpecKey, PhoneSpecs } from "./phoneSpecs";
import type { Condition, Device, UpgradePreferences } from "./types";

/**
 * Adapters from backend/database shapes to the `Device` the UI renders.
 * Pages should fetch data, pass it through `deviceFromApi`, and hand the
 * result to `DeviceGrid` / `PhoneCard` — components never see raw API data.
 */

/**
 * JSON returned by `GET /api/devices` and `GET /api/devices/{id}`
 * (backend `dto/DeviceResponse.java`). JSONB columns arrive as JSON-encoded strings.
 */
export type DeviceResponse = {
  id: number;
  productId: number | null;
  productBrand: string | null;
  productModelName: string | null;
  /** `products.category` of the linked product (e.g. "SMARTPHONE"); null when unlinked. */
  productCategory?: string | null;
  customName: string | null;
  purchaseDate: string | null;
  condition: string | null;
  satisfactionScore: number | null;
  useCases: string;
  specOverrides: string;
  /** `device_preferences.budget`; null when the device has no preferences row. */
  budget: number | null;
  /** `device_preferences.currency`; null when there is no budget. */
  currency: string | null;
  upgradeUrgency: string | null;
  brandFlexibility: string | null;
  priorities: string | null;
  painPoints: string | null;
  notes: string | null;
  current: boolean;
  createdAt: string;
  updatedAt: string;
};

/**
 * Body for `POST/PUT /api/devices` (backend `dto/DeviceRequest.java`). The
 * backend needs either `productId` or a non-blank `customName`.
 */
export type DeviceRequest = {
  productId: number | null;
  customName: string | null;
  purchaseDate: string | null;
  condition: string | null;
  satisfactionScore: number | null;
  useCases: string;
  specOverrides: string;
  /**
   * Upgrade budget, saved to `device_preferences`. Null leaves any saved
   * budget unchanged (the backend never clears it).
   */
  budget: number | null;
  /** ISO 4217 code for `budget`; null when no budget is sent. */
  currency: string | null;
  /** Omitted preference fields preserve their saved values. */
  upgradeUrgency?: string | null;
  brandFlexibility?: string | null;
  /** JSON-encoded factor weights, matching `device_preferences.priorities`. */
  priorities?: string;
  /** JSON-encoded text or array of text. */
  painPoints?: string;
  notes?: string;
};

const snake = (key: string) =>
  key.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);

/**
 * Builds the request body for saving a device. A device picked from the
 * catalogue sends its `productId`; otherwise the name goes in `customName`.
 * Device type is not stored by the backend; it comes from the linked
 * product's category.
 *
 * Only `device.specOverrides` is sent, keyed by `phone` column name as the
 * backend reads them. `device.specs` is catalogue + overrides merged, so
 * sending it would copy catalogue values into overrides.
 * A cleared spec is sent as `null`, which the backend reads as "unknown"
 * rather than falling back to the catalogue value.
 *
 * The budget and its currency come from `device.upgradePreferences`. A device
 * with no budget sends null for both and omits the other preferences.
 * Saved weights are retained; newly selected priorities receive weight 1.
 */
export function deviceToRequest(device: Device): DeviceRequest {
  const name = `${device.brand} ${device.model}`.trim();
  const useCases = device.use.split(",").map((use) => use.trim()).filter(Boolean);
  const budget = device.upgradePreferences?.budget ?? null;
  return {
    productId: device.productId ?? null,
    customName: device.productId ? null : name || null,
    purchaseDate: device.purchaseDate || null,
    condition: device.condition ?? null,
    satisfactionScore: device.satisfaction ?? null,
    useCases: JSON.stringify(useCases),
    specOverrides: JSON.stringify(
      Object.fromEntries(
        Object.entries(device.specOverrides ?? {}).map(([k, v]) => [snake(k), v])
      )
    ),
    budget,
    currency:
      budget == null
        ? null
        : (device.upgradePreferences.currency ?? DEFAULT_CURRENCY),
    ...(budget == null ? {} : preferencesToRequest(device.upgradePreferences)),
  };
}

/** Extra data the device endpoint does not return yet. */
export type DeviceExtras = {
  /** `products.category`, e.g. "SMARTPHONE"; overrides `response.productCategory`. */
  category?: string | null;
  /** The catalogue `phone` row for this product — camelCase or snake_case. */
  phone?: Record<string, unknown> | null;
  image?: string;
  primary?: boolean;
};

const CATEGORY_TO_TYPE: Record<string, string> = {
  SMARTPHONE: "Phone",
  PHONE: "Phone",
  LAPTOP: "Laptop",
  TABLET: "Tablet",
  MONITOR: "Monitor",
  DESKTOP: "Desktop PC",
};

function parseJson(text: string | null | undefined): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function painPointsText(raw: string | null | undefined): string {
  const value = parseJson(raw);
  if (typeof value === "string") return value;
  return Array.isArray(value) ? value.join("; ") : "";
}

function preferencesToRequest(prefs: UpgradePreferences) {
  const savedPainPoints = parseJson(prefs.painPointsJson);
  const supportedPainPoints = typeof savedPainPoints === "string"
    || Array.isArray(savedPainPoints) && savedPainPoints.every((point) => typeof point === "string");
  return {
    upgradeUrgency: prefs.urgency || null,
    brandFlexibility: prefs.brandFlex || null,
    priorities: JSON.stringify(Object.fromEntries(
      prefs.priorities.map((label) => {
        const factor = PRIORITY_FACTORS[label] ?? label;
        return [factor, prefs.priorityWeights?.[factor] ?? 1];
      })
    )),
    // Budget-only records predate this API and can contain the JSONB default {}.
    // Preserve supported strings/arrays; encode that legacy empty value as text.
    painPoints: prefs.painPointsJson && supportedPainPoints && painPointsText(prefs.painPointsJson) === prefs.painPoints
      ? prefs.painPointsJson
      : JSON.stringify(prefs.painPoints),
    notes: prefs.notes,
  };
}

function preferencesFromApi(response: DeviceResponse): UpgradePreferences {
  if (response.budget == null) return defaultPrefs();
  const parsed = parseJson(response.priorities);
  const priorityWeights = parsed && typeof parsed === "object" && !Array.isArray(parsed)
    ? Object.fromEntries(Object.entries(parsed).filter(([, weight]) => Number.isInteger(weight))) as Record<string, number>
    : {};
  return {
    budget: Number(response.budget),
    currency: response.currency,
    priorities: Object.keys(priorityWeights).map((factor) =>
      Object.keys(PRIORITY_FACTORS).find((label) => PRIORITY_FACTORS[label] === factor) ?? factor
    ),
    priorityWeights,
    urgency: response.upgradeUrgency ?? "",
    brandFlex: response.brandFlexibility ?? "",
    painPoints: painPointsText(response.painPoints),
    painPointsJson: response.painPoints ?? undefined,
    notes: response.notes ?? "",
  };
}

const camel = (key: string) =>
  key.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());

/**
 * Keeps only known phone columns from a raw object, accepting snake_case
 * (`battery_mah`) or camelCase (`batteryMah`) keys. Postgres NUMERIC can
 * arrive as a string, so numeric-looking strings become numbers.
 */
export function normalizePhoneSpecs(raw: unknown): PhoneSpecs {
  if (!raw || typeof raw !== "object") return {};
  const specs: Record<string, string | number | null> = {};
  for (const [key, value] of Object.entries(raw)) {
    const k = camel(key) as PhoneSpecKey;
    if (!PHONE_SPEC_KEYS.includes(k)) continue;
    if (typeof value === "number" || value === null) {
      specs[k] = value;
    } else if (typeof value === "string") {
      const n = Number(value);
      const numeric = !TEXT_SPEC_KEYS.has(k) && value.trim() !== "" && !Number.isNaN(n);
      specs[k] = numeric ? n : value;
    }
  }
  return specs as PhoneSpecs;
}

function parseCondition(value: string | null): Condition | undefined {
  return CONDITIONS.find(
    (c) => c.toLowerCase() === value?.trim().toLowerCase()
  );
}

function parseUseCases(text: string): string {
  const parsed = parseJson(text);
  return Array.isArray(parsed) ? parsed.map(String).join(", ") : "";
}

/**
 * Builds a UI `Device` from a `DeviceResponse`. Phone specs are the catalogue
 * row with the user's `spec_overrides` applied on top, mirroring the schema's
 * intent that overrides never mutate the shared product.
 */
export function deviceFromApi(
  response: DeviceResponse,
  extras: DeviceExtras = {}
): Device {
  const category = (extras.category ?? response.productCategory)?.toUpperCase();
  const overrides = normalizePhoneSpecs(parseJson(response.specOverrides));
  const hasPhone = Boolean(extras.phone) || Object.keys(overrides).length > 0;
  const type = category
    ? (CATEGORY_TO_TYPE[category] ?? "Other")
    : hasPhone
      ? "Phone"
      : "Other";

  return {
    id: String(response.id),
    productId: response.productId,
    type,
    brand: response.productBrand ?? "",
    model: response.productModelName ?? response.customName ?? "Unnamed device",
    image: extras.image,
    condition: parseCondition(response.condition),
    primary: extras.primary ?? false,
    use: parseUseCases(response.useCases),
    purchaseDate: response.purchaseDate ?? "",
    satisfaction: response.satisfactionScore ?? undefined,
    upgradePreferences: preferencesFromApi(response),
    specs:
      type === "Phone"
        ? { ...normalizePhoneSpecs(extras.phone), ...overrides }
        : undefined,
    specOverrides: overrides,
  };
}
