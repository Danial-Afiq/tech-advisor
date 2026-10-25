import type {
  SmartphoneCatalogueItem,
  SmartphoneCatalogueRequest,
} from "../../api/catalogue";
import { catalogueSpecs, formToSpecs, specsToForm } from "../devices/catalogue";
import type { SpecFormValues } from "../devices/catalogue";
import { PHONE_SPEC_KEYS } from "../devices/phoneSpecs";
import { formatRecommendationPrice } from "../recommendations/recommendationPresentation";

/**
 * Search, filter, sort and paging for the admin catalogue table. All of it is
 * client-side: `GET /api/catalogue/smartphones` returns the whole catalogue,
 * which is a few hundred rows.
 */

export const CATALOGUE_STATUSES = ["VERIFIED", "UNVERIFIED"] as const;
export const PAGE_SIZES = [10, 25, 50, 100] as const;
export const ALL = "";

export type SpecCompleteness = "" | "complete" | "missing";
export type PriceAvailability = "" | "priced" | "unpriced";

export type CatalogueFilters = {
  query: string;
  brand: string;
  status: string;
  osFamily: string;
  specs: SpecCompleteness;
  price: PriceAvailability;
  /** Inclusive price bounds as typed; blank means no bound. */
  minPrice: string;
  maxPrice: string;
};

export const EMPTY_FILTERS: CatalogueFilters = {
  query: "",
  brand: ALL,
  status: ALL,
  osFamily: ALL,
  specs: "",
  price: "",
  minPrice: "",
  maxPrice: "",
};

/** Preset price ranges; picking one fills the editable min/max bounds. */
export const PRICE_RANGES = [
  { value: "0-500", label: "0 – 500", min: "0", max: "500" },
  { value: "500-1000", label: "500 – 1,000", min: "500", max: "1000" },
  { value: "1000-1500", label: "1,000 – 1,500", min: "1000", max: "1500" },
  { value: "1500-2000", label: "1,500 – 2,000", min: "1500", max: "2000" },
  { value: "2000-", label: "2,000 and above", min: "2000", max: "" },
] as const;

export const ANY_PRICE_RANGE = "";
export const CUSTOM_PRICE_RANGE = "custom";

/** The preset matching the typed bounds, "" for none, else "custom". */
export function priceRangeValue(filters: CatalogueFilters): string {
  const min = filters.minPrice.trim();
  const max = filters.maxPrice.trim();
  if (!min && !max) return ANY_PRICE_RANGE;
  const preset = PRICE_RANGES.find((r) => r.min === min && r.max === max);
  return preset ? preset.value : CUSTOM_PRICE_RANGE;
}

/** The bounds a range choice sets; "custom" keeps what was typed. */
export function boundsForRange(
  value: string,
  current: Pick<CatalogueFilters, "minPrice" | "maxPrice">
): Pick<CatalogueFilters, "minPrice" | "maxPrice"> {
  if (value === ANY_PRICE_RANGE) return { minPrice: "", maxPrice: "" };
  const preset = PRICE_RANGES.find((r) => r.value === value);
  return preset ? { minPrice: preset.min, maxPrice: preset.max } : current;
}

const bound = (text: string) => {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  return Number.isNaN(n) ? null : n;
};

type SortOption = {
  value: string;
  label: string;
  compare: (a: SmartphoneCatalogueItem, b: SmartphoneCatalogueItem) => number;
};

const text = (a: string | null, b: string | null) =>
  (a ?? "").localeCompare(b ?? "", undefined, { sensitivity: "base", numeric: true });

/** Unknown values always sort last, whichever direction is chosen. */
const nullsLast = (
  a: number | string | null,
  b: number | string | null,
  descending: boolean
) => {
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  const diff = Number(a) - Number(b);
  return descending ? -diff : diff;
};

const byName = (a: SmartphoneCatalogueItem, b: SmartphoneCatalogueItem) =>
  text(a.brand, b.brand) || text(a.modelName, b.modelName);

const releaseTime = (date: string | null) => (date ? Date.parse(date) : null);

export const SORT_OPTIONS: SortOption[] = [
  { value: "brand-asc", label: "Brand A–Z", compare: byName },
  { value: "brand-desc", label: "Brand Z–A", compare: (a, b) => byName(b, a) },
  {
    value: "model-asc",
    label: "Model A–Z",
    compare: (a, b) => text(a.modelName, b.modelName) || text(a.brand, b.brand),
  },
  {
    value: "release-desc",
    label: "Newest release",
    compare: (a, b) =>
      nullsLast(releaseTime(a.releaseDate), releaseTime(b.releaseDate), true) || byName(a, b),
  },
  {
    value: "release-asc",
    label: "Oldest release",
    compare: (a, b) =>
      nullsLast(releaseTime(a.releaseDate), releaseTime(b.releaseDate), false) || byName(a, b),
  },
  {
    value: "price-asc",
    label: "Lowest price",
    compare: (a, b) => nullsLast(a.latestPrice, b.latestPrice, false) || byName(a, b),
  },
  {
    value: "price-desc",
    label: "Highest price",
    compare: (a, b) => nullsLast(a.latestPrice, b.latestPrice, true) || byName(a, b),
  },
  {
    value: "ram-desc",
    label: "Most RAM",
    compare: (a, b) => nullsLast(a.ramGb, b.ramGb, true) || byName(a, b),
  },
  {
    value: "storage-desc",
    label: "Most storage",
    compare: (a, b) => nullsLast(a.storageGb, b.storageGb, true) || byName(a, b),
  },
  {
    value: "battery-desc",
    label: "Biggest battery",
    compare: (a, b) => nullsLast(a.batteryMah, b.batteryMah, true) || byName(a, b),
  },
  { value: "id-desc", label: "Recently added", compare: (a, b) => b.id - a.id },
];

export const DEFAULT_SORT = SORT_OPTIONS[0].value;

/** "Android 14" and "Android 15, One UI 7" both belong to "Android". */
export function osFamily(os: string | null): string {
  const first = os?.trim().split(/[\s,(/]+/)[0];
  return first || "Unknown";
}

/** Number of `phone` columns that are empty for this entry. */
export const missingSpecCount = (item: SmartphoneCatalogueItem) =>
  PHONE_SPEC_KEYS.filter((key) => {
    const value = item[key];
    return value === null || value === undefined || value === "";
  }).length;

const searchText = (item: SmartphoneCatalogueItem) =>
  [item.id, item.brand, item.modelName, item.chipset, item.os, item.ipRating, item.status]
    .filter((v) => v !== null && v !== undefined)
    .join(" ")
    .toLowerCase();

/** Distinct, sorted filter choices taken from the loaded catalogue. */
export function filterChoices(items: SmartphoneCatalogueItem[]) {
  const sorted = (values: Iterable<string>) =>
    [...new Set(values)].sort((a, b) => text(a, b));
  return {
    brands: sorted(items.map((i) => i.brand)),
    osFamilies: sorted(items.map((i) => osFamily(i.os))),
  };
}

/** Entries matching every filter; each search word must appear somewhere. */
export function applyFilters(
  items: SmartphoneCatalogueItem[],
  filters: CatalogueFilters
): SmartphoneCatalogueItem[] {
  const words = filters.query.toLowerCase().split(/\s+/).filter(Boolean);
  return items.filter((item) => {
    if (filters.brand && item.brand !== filters.brand) return false;
    if (filters.status && item.status !== filters.status) return false;
    if (filters.osFamily && osFamily(item.os) !== filters.osFamily) return false;
    if (filters.specs === "complete" && missingSpecCount(item) > 0) return false;
    if (filters.specs === "missing" && missingSpecCount(item) === 0) return false;
    if (filters.price === "priced" && item.latestPrice === null) return false;
    if (filters.price === "unpriced" && item.latestPrice !== null) return false;
    const min = bound(filters.minPrice);
    const max = bound(filters.maxPrice);
    if (min !== null || max !== null) {
      // A price range only matches priced entries. Prices are compared as
      // stored, whatever their currency, like the rest of the system.
      if (item.latestPrice === null) return false;
      const price = Number(item.latestPrice);
      if (min !== null && price < min) return false;
      if (max !== null && price > max) return false;
    }
    if (words.length === 0) return true;
    const haystack = searchText(item);
    return words.every((word) => haystack.includes(word));
  });
}

export function sortItems(items: SmartphoneCatalogueItem[], sort: string) {
  const option = SORT_OPTIONS.find((o) => o.value === sort) ?? SORT_OPTIONS[0];
  return [...items].sort(option.compare);
}

/** "SGD 849.00", or null when the entry has no price history. */
export const catalogueItemPrice = (item: SmartphoneCatalogueItem) =>
  item.latestPrice === null
    ? null
    : formatRecommendationPrice(Number(item.latestPrice), item.currency);

export const pageCount = (total: number, size: number) =>
  Math.max(1, Math.ceil(total / size));

/** The page's slice, with `page` (1-based) clamped into range. */
export function pageOf<T>(items: T[], page: number, size: number) {
  const current = Math.min(Math.max(1, page), pageCount(items.length, size));
  return {
    page: current,
    rows: items.slice((current - 1) * size, current * size),
  };
}

/**
 * Page buttons to show: always the first and last page and two either side
 * of the current one, with "…" for the gaps, e.g. 1 … 4 5 6 7 8 … 30.
 */
export function pageButtons(current: number, total: number): (number | "…")[] {
  const pages = new Set([1, total]);
  for (let p = current - 2; p <= current + 2; p++) {
    if (p >= 1 && p <= total) pages.add(p);
  }
  const ordered = [...pages].sort((a, b) => a - b);
  return ordered.flatMap((p, i) =>
    i > 0 && p - ordered[i - 1] > 1 ? (["…", p] as const) : [p]
  );
}

/** What the add/edit form holds; inputs work in strings. */
export type CatalogueFormValues = {
  brand: string;
  modelName: string;
  releaseDate: string;
  status: string;
  specs: SpecFormValues;
};

export function itemToForm(item?: SmartphoneCatalogueItem): CatalogueFormValues {
  return {
    brand: item?.brand ?? "",
    modelName: item?.modelName ?? "",
    releaseDate: item?.releaseDate ?? "",
    status: item?.status ?? "VERIFIED",
    specs: specsToForm(item ? catalogueSpecs(item) : undefined),
  };
}

/** The request body; blank specs are sent as null so a PUT clears them. */
export function formToRequest(values: CatalogueFormValues): SmartphoneCatalogueRequest {
  const filled = formToSpecs(values.specs);
  const specs = Object.fromEntries(
    PHONE_SPEC_KEYS.map((key) => [key, filled[key] ?? null])
  ) as Pick<SmartphoneCatalogueRequest, (typeof PHONE_SPEC_KEYS)[number]>;
  return {
    brand: values.brand.trim(),
    modelName: values.modelName.trim(),
    releaseDate: values.releaseDate || null,
    status: values.status,
    ...specs,
  };
}
