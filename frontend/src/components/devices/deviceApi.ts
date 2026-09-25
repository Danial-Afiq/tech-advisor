import { CONDITIONS, defaultPrefs } from "./deviceOptions";
import { PHONE_SPEC_KEYS } from "./phoneSpecs";
import type { PhoneSpecKey, PhoneSpecs } from "./phoneSpecs";
import type { Condition, Device, UpgradePreferences } from "./types";

/**
 * Adapters from backend/database shapes to the `Device` the UI renders.
 * Pages should fetch data, pass it through `deviceFromApi`, and hand the
 * result to `DeviceGrid` / `PhoneCard` — components never see raw API data.
 */

/**
 * JSON returned by `GET /api/devices` and `GET /api/devices/{id}`
 * (backend `dto/DeviceResponse.java`). `useCases` and `specOverrides` are
 * JSONB columns that arrive as JSON-encoded strings.
 */
export type DeviceResponse = {
  id: number;
  productId: number | null;
  productBrand: string | null;
  productModelName: string | null;
  customName: string | null;
  purchaseDate: string | null;
  condition: string | null;
  satisfactionScore: number | null;
  useCases: string;
  specOverrides: string;
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
};

/**
 * Builds the request body for saving a device. Without a catalogue link the
 * name goes in `customName` (there is no product-search endpoint yet, so the
 * form can't pick a `productId`). Device type is not stored by the backend;
 * it comes from the linked product's category.
 *
 * `specOverrides` is sent empty: `device.specs` is catalogue + overrides
 * merged, so sending it back would copy catalogue values into overrides.
 */
export function deviceToRequest(device: Device): DeviceRequest {
  const name = `${device.brand} ${device.model}`.trim();
  const use = device.use.trim();
  return {
    productId: device.productId ?? null,
    customName: device.productId ? null : name || null,
    purchaseDate: device.purchaseDate || null,
    condition: device.condition ?? null,
    satisfactionScore: device.satisfaction ?? null,
    useCases: JSON.stringify(use ? [use] : []),
    specOverrides: "{}",
  };
}

/** Extra data the device endpoint does not return yet. */
export type DeviceExtras = {
  /** `products.category`, e.g. "SMARTPHONE". */
  category?: string | null;
  /** The catalogue `phone` row for this product — camelCase or snake_case. */
  phone?: Record<string, unknown> | null;
  preferences?: UpgradePreferences | null;
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

const TEXT_SPECS = new Set<PhoneSpecKey>([
  "chipset",
  "cameraSpecs",
  "ipRating",
  "os",
]);

function parseJson(text: string | null | undefined): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
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
      const numeric = !TEXT_SPECS.has(k) && value.trim() !== "" && !Number.isNaN(n);
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
  const category = extras.category?.toUpperCase();
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
    upgradePreferences: extras.preferences ?? defaultPrefs(),
    specs:
      type === "Phone"
        ? { ...normalizePhoneSpecs(extras.phone), ...overrides }
        : undefined,
  };
}
