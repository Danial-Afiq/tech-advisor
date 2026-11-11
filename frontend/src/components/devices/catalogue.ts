import type { GpuCatalogueItem, SmartphoneCatalogueItem } from "../../api/catalogue";
import { normalizePhoneSpecs, normalizeSpecs } from "./deviceApi";
import { formatSpec } from "./phoneSpecs";
import {
  ALL_SPEC_KEYS,
  GPU_SPEC_SCHEMA,
  PHONE_SPEC_SCHEMA,
} from "./specSchemas";
import type { DeviceSpecs, SpecKey, SpecSchema } from "./specSchemas";
import type { Device } from "./types";

/**
 * Helpers for prefilling the device form from the smartphone and GPU
 * catalogues. Matching is client-side: each list is fetched once per page.
 */

/** A catalogue product the device form can link to. */
export type CatalogueItem = SmartphoneCatalogueItem | GpuCatalogueItem;

export const isGpuItem = (item: CatalogueItem): item is GpuCatalogueItem =>
  "vramGb" in item;

/** The device type a catalogue item belongs to ("Phone" or "GPU"). */
export const catalogueType = (item: CatalogueItem) => (isGpuItem(item) ? "GPU" : "Phone");

const schemaOf = (item: CatalogueItem) =>
  isGpuItem(item) ? GPU_SPEC_SCHEMA : PHONE_SPEC_SCHEMA;

export const catalogueName = (item: CatalogueItem) =>
  `${item.brand} ${item.modelName}`.trim();

/** The catalogue item's `phone` or `gpu` columns as UI specs. */
export function catalogueSpecs(item: CatalogueItem): DeviceSpecs {
  if (!isGpuItem(item)) return normalizePhoneSpecs(item);
  return normalizeSpecs(
    {
      architecture: item.architecture,
      baseClockMhz: item.baseClockMhz,
      boostClockMhz: item.boostClockMhz,
      vram: item.vramGb,
      memoryType: item.memoryType,
      busWidth: item.memoryBusBits,
      tdpWatts: item.tdpWatts,
    },
    GPU_SPEC_SCHEMA.keys
  );
}

/**
 * A linked device with its catalogue specs and product image filled in (user
 * overrides on top). `GET /api/devices` doesn't return either, so pages add
 * them from the catalogue lists.
 */
export function withCatalogue(device: Device, item: CatalogueItem | undefined): Device {
  if (!item) return device;
  return {
    ...device,
    type: catalogueType(item),
    image: item.imageUrl ?? device.image,
    specs: { ...catalogueSpecs(item), ...device.specOverrides },
  };
}

const fieldOf = (schema: SpecSchema, key: SpecKey) =>
  schema.groups.flatMap((g) => g.fields).find((f) => f.key === key)!;

/**
 * Short spec line for a suggestion, e.g. "Apple A17 Pro · 256 GB · 8 GB RAM"
 * or "12 GB · GDDR6X · Ada Lovelace".
 */
export function catalogueSummary(item: CatalogueItem): string {
  const schema = schemaOf(item);
  const specs = catalogueSpecs(item);
  return schema.summary
    .map((key) => {
      const text = formatSpec(fieldOf(schema, key), specs[key]);
      return text && key === "ramGb" ? `${text} RAM` : text;
    })
    .filter(Boolean)
    .join(" · ");
}

const words = (text: string) =>
  text.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);

type IndexedItem<T> = {
  item: T;
  /** Words of "brand model", lowercased. */
  name: string[];
  brand: string;
  model: string;
};

// Lowercased names, built once per catalogue list instead of on every
// keystroke. Keyed by the array, which stays the same while the list is.
const indexes = new WeakMap<CatalogueItem[], IndexedItem<CatalogueItem>[]>();

function indexed<T extends CatalogueItem>(items: T[]): IndexedItem<T>[] {
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
  return index as IndexedItem<T>[];
}

/**
 * Catalogue items whose name matches what the user typed. Every typed word
 * must start a word of "brand model", so "s2" finds "Galaxy S24" but "15"
 * does not find "Galaxy A15". A matching `brand` input ranks items first.
 */
export function matchCatalogue<T extends CatalogueItem>(
  items: T[],
  query: string,
  brand = "",
  limit = 8
): T[] {
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

/**
 * Editable spec values as the form holds them (inputs work in strings). It
 * holds every phone and GPU key, so switching device type never leaves an
 * input without a value.
 */
export type SpecFormValues = Record<SpecKey, string>;

export function specsToForm(specs?: DeviceSpecs): SpecFormValues {
  return Object.fromEntries(
    ALL_SPEC_KEYS.map((key) => {
      const value = specs?.[key];
      return [key, value === null || value === undefined ? "" : String(value)];
    })
  ) as SpecFormValues;
}

/** A field that had a baseline (prefilled) value and is now empty. */
export const isCleared = (
  values: SpecFormValues,
  baseline: SpecFormValues,
  key: SpecKey
) => baseline[key].trim() !== "" && values[key].trim() === "";

/** Emptied prefilled fields the user hasn't yet confirmed as empty on purpose. */
export const unconfirmedClears = (
  values: SpecFormValues,
  baseline: SpecFormValues,
  keptEmpty: ReadonlySet<SpecKey>,
  schema: SpecSchema = PHONE_SPEC_SCHEMA
): SpecKey[] =>
  schema.keys.filter(
    (key) => isCleared(values, baseline, key) && !keptEmpty.has(key)
  );

/** Filled-in values of `schema` only; blank or unparseable numbers are left out. */
export function formToSpecs(
  values: SpecFormValues,
  schema: SpecSchema = PHONE_SPEC_SCHEMA
): DeviceSpecs {
  const specs: Record<string, string | number> = {};
  for (const key of schema.keys) {
    const text = values[key].trim();
    if (!text) continue;
    if (schema.text.has(key)) {
      specs[key] = text;
    } else {
      const n = Number(text);
      if (!Number.isNaN(n)) specs[key] = n;
    }
  }
  return specs as DeviceSpecs;
}

/**
 * The values in `specs` that differ from `baseline`. Saved as the device's
 * `spec_overrides`, so the shared catalogue row is never copied or mutated.
 * A baseline value missing from `specs` was cleared by the user and comes
 * back as `null`, so it stays blank instead of falling back to the catalogue.
 */
export function diffSpecs(
  specs: DeviceSpecs,
  baseline: DeviceSpecs,
  schema: SpecSchema = PHONE_SPEC_SCHEMA
): DeviceSpecs {
  return Object.fromEntries(
    schema.keys
      .map((key) => [key, specs[key] ?? null] as const)
      .filter(([key, value]) => (baseline[key] ?? null) !== value)
  ) as DeviceSpecs;
}
