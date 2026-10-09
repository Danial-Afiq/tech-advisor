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

const SUMMARY_KEYS: PhoneSpecKey[] = ["chipset", "storageGb", "ramGb"];

/** Short spec line for a suggestion, e.g. "Apple A17 Pro · 256 GB · 8 GB RAM". */
export function catalogueSummary(item: SmartphoneCatalogueItem): string {
  const specs = catalogueSpecs(item);
  const fields = PHONE_SPEC_GROUPS.flatMap((g) => g.fields);
  return SUMMARY_KEYS.map((key) => {
    const field = fields.find((f) => f.key === key);
    const text = field && formatSpec(field, specs[key]);
    return text && key === "ramGb" ? `${text} RAM` : text;
  })
    .filter(Boolean)
    .join(" · ");
}

const words = (text: string) =>
  text.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);

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

  return items
    .map((item, index) => {
      const name = words(catalogueName(item));
      if (!tokens.every((t) => name.some((w) => w.startsWith(t)))) return null;
      let rank = 0;
      if (brandKey && item.brand.toLowerCase().startsWith(brandKey)) rank -= 2;
      if (item.modelName.toLowerCase().startsWith(query.trim().toLowerCase())) rank -= 1;
      return { item, rank, index };
    })
    .filter((m) => m !== null)
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
 */
export function diffSpecs(specs: PhoneSpecs, baseline: PhoneSpecs): PhoneSpecs {
  return Object.fromEntries(
    Object.entries(specs).filter(
      ([key, value]) => baseline[key as PhoneSpecKey] !== value
    )
  ) as PhoneSpecs;
}
