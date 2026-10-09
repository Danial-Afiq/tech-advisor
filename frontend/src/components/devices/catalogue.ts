import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import { normalizePhoneSpecs } from "./deviceApi";
import {
  PHONE_SPEC_GROUPS,
  PHONE_SPEC_KEYS,
  TEXT_SPEC_KEYS,
  formatSpec,
} from "./phoneSpecs";
import type { PhoneSpecKey, PhoneSpecs } from "./phoneSpecs";
import type { Device } from "./types";

/**
 * Helpers for prefilling the device form from the smartphone catalogue.
 * Matching is client-side: the catalogue list is fetched once per page.
 */

export const catalogueName = (item: SmartphoneCatalogueItem) =>
  `${item.brand} ${item.modelName}`.trim();

/** The catalogue item's `phone` columns as UI `PhoneSpecs`. */
export const catalogueSpecs = (item: SmartphoneCatalogueItem): PhoneSpecs =>
  normalizePhoneSpecs(item);

/**
 * A linked device with its catalogue specs filled in (user overrides on top).
 * `GET /api/devices` doesn't return the specs, so pages add them from the
 * catalogue list.
 */
export function withCatalogue(
  device: Device,
  item: SmartphoneCatalogueItem | undefined
): Device {
  if (!item) return device;
  return {
    ...device,
    type: "Phone",
    specs: { ...catalogueSpecs(item), ...device.specOverrides },
  };
}

/** The fields a suggestion's summary line shows, in order. */
const SUMMARY_FIELDS = (["chipset", "storageGb", "ramGb"] as PhoneSpecKey[]).map(
  (key) => PHONE_SPEC_GROUPS.flatMap((g) => g.fields).find((f) => f.key === key)!
);

/** Short spec line for a suggestion, e.g. "Apple A17 Pro · 256 GB · 8 GB RAM". */
export function catalogueSummary(item: SmartphoneCatalogueItem): string {
  const specs = catalogueSpecs(item);
  return SUMMARY_FIELDS.map((field) => {
    const text = formatSpec(field, specs[field.key]);
    return text && field.key === "ramGb" ? `${text} RAM` : text;
  })
    .filter(Boolean)
    .join(" · ");
}

const words = (text: string) =>
  text.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);

type IndexedItem = {
  item: SmartphoneCatalogueItem;
  /** Words of "brand model", lowercased. */
  name: string[];
  brand: string;
  model: string;
};

// Lowercased names, built once per catalogue list instead of on every
// keystroke. Keyed by the array, which stays the same while the list is.
const indexes = new WeakMap<SmartphoneCatalogueItem[], IndexedItem[]>();

function indexed(items: SmartphoneCatalogueItem[]): IndexedItem[] {
  let index = indexes.get(items);
  if (!index) {
    index = items.map((item) => ({
      item,
      name: words(catalogueName(item)),
      brand: item.brand.toLowerCase(),
      model: item.modelName.toLowerCase(),
    }));
    indexes.set(items, index);
  }
  return index;
}

/**
 * Catalogue items whose name matches what the user typed. Every typed word
 * must start a word of "brand model", so "s2" finds "Galaxy S24" but "15"
 * does not find "Galaxy A15". A matching `brand` input ranks items first.
 */
export function matchCatalogue(
  items: SmartphoneCatalogueItem[],
  query: string,
  brand = "",
  limit = 8
): SmartphoneCatalogueItem[] {
  const tokens = words(query);
  if (tokens.length === 0) return [];
  const brandKey = brand.trim().toLowerCase();
  const queryKey = query.trim().toLowerCase();

  return indexed(items)
    .flatMap((entry, index) => {
      if (!tokens.every((t) => entry.name.some((w) => w.startsWith(t)))) return [];
      let rank = 0;
      if (brandKey && entry.brand.startsWith(brandKey)) rank -= 2;
      if (entry.model.startsWith(queryKey)) rank -= 1;
      return [{ item: entry.item, rank, index }];
    })
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .slice(0, limit)
    .map((m) => m.item);
}

/** Editable spec values as the form holds them (inputs work in strings). */
export type SpecFormValues = Record<PhoneSpecKey, string>;

export function specsToForm(specs?: PhoneSpecs): SpecFormValues {
  return Object.fromEntries(
    PHONE_SPEC_KEYS.map((key) => {
      const value = specs?.[key];
      return [key, value === null || value === undefined ? "" : String(value)];
    })
  ) as SpecFormValues;
}

/** Filled-in values only; blank or unparseable numbers are left out. */
export function formToSpecs(values: SpecFormValues): PhoneSpecs {
  const specs: Record<string, string | number> = {};
  for (const key of PHONE_SPEC_KEYS) {
    const text = values[key].trim();
    if (!text) continue;
    if (TEXT_SPEC_KEYS.has(key)) {
      specs[key] = text;
    } else {
      const n = Number(text);
      if (!Number.isNaN(n)) specs[key] = n;
    }
  }
  return specs as PhoneSpecs;
}

/**
 * The values in `specs` that differ from `baseline`. Saved as the device's
 * `spec_overrides`, so the shared catalogue row is never copied or mutated.
 * A baseline value missing from `specs` was cleared by the user and comes
 * back as `null`, so it stays blank instead of falling back to the catalogue.
 */
export function diffSpecs(specs: PhoneSpecs, baseline: PhoneSpecs): PhoneSpecs {
  return Object.fromEntries(
    PHONE_SPEC_KEYS.map((key) => [key, specs[key] ?? null] as const).filter(
      ([key, value]) => (baseline[key] ?? null) !== value
    )
  ) as PhoneSpecs;
}
