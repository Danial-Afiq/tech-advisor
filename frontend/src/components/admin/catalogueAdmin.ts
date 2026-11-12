import {
  createGpu,
  createSmartphone,
  deleteGpu,
  deleteSmartphone,
  listGpus,
  listSmartphones,
  updateGpu,
  updateSmartphone,
} from "../../api/catalogue";
import type {
  CatalogueBenchmarkRequest,
  GpuCatalogueItem,
  GpuCatalogueRequest,
  SmartphoneCatalogueItem,
  SmartphoneCatalogueRequest,
  SmartphonePriceRequest,
} from "../../api/catalogue";
import { DEFAULT_CURRENCY } from "../currency/currency";
import { catalogueSpecs, formToSpecs, specsToForm } from "../devices/catalogue";
import type { SpecFormValues } from "../devices/catalogue";
import { PHONE_SPEC_KEYS } from "../devices/phoneSpecs";
import { formatRecommendationPrice } from "../recommendations/recommendationPresentation";

/**
 * Search, filter, sort and paging for the admin catalogue table. All of it is
 * client-side: the list routes return the whole catalogue of one kind, which
 * is at most about a thousand rows. A `CatalogueKind` (`PHONE_KIND`,
 * `GPU_KIND`) supplies what differs between smartphones and GPUs.
 */

/** The `products` fields every catalogue kind shares. */
export type CatalogueItemBase = Pick<
  SmartphoneCatalogueItem,
  "id" | "brand" | "modelName" | "releaseDate" | "status" | "latestPrice" | "currency"
>;

export const CATALOGUE_STATUSES = ["VERIFIED", "UNVERIFIED"] as const;
export const PAGE_SIZES = [10, 25, 50, 100] as const;
export const ALL = "";

export type SpecCompleteness = "" | "complete" | "missing";
export type PriceAvailability = "" | "priced" | "unpriced";

export type CatalogueFilters = {
  query: string;
  brand: string;
  status: string;
  /** The kind's family filter: OS family for smartphones, architecture for GPUs. */
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

// Method syntax keeps a kind's options assignable to the base item type.
type SortOption<T extends CatalogueItemBase = SmartphoneCatalogueItem> = {
  value: string;
  label: string;
  compare(a: T, b: T): number;
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

const byName = (a: CatalogueItemBase, b: CatalogueItemBase) =>
  text(a.brand, b.brand) || text(a.modelName, b.modelName);

const releaseTime = (date: string | null) => (date ? Date.parse(date) : null);

/** Shared sorts, then the kind's own spec sorts, then "Recently added". */
const catalogueSorts = <T extends CatalogueItemBase>(specific: SortOption<T>[]): SortOption<T>[] => [
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
  ...specific,
  { value: "id-desc", label: "Recently added", compare: (a, b) => b.id - a.id },
];

export const SORT_OPTIONS = catalogueSorts<SmartphoneCatalogueItem>([
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
]);

export const GPU_SORT_OPTIONS = catalogueSorts<GpuCatalogueItem>([
  {
    value: "vram-desc",
    label: "Most VRAM",
    compare: (a, b) => nullsLast(a.vramGb, b.vramGb, true) || byName(a, b),
  },
  {
    value: "boost-desc",
    label: "Fastest boost clock",
    compare: (a, b) => nullsLast(a.boostClockMhz, b.boostClockMhz, true) || byName(a, b),
  },
  {
    value: "tdp-asc",
    label: "Lowest TDP",
    compare: (a, b) => nullsLast(a.tdpWatts, b.tdpWatts, false) || byName(a, b),
  },
]);

export const DEFAULT_SORT = SORT_OPTIONS[0].value;

/** "Android 14" and "Android 15, One UI 7" both belong to "Android". */
export function osFamily(os: string | null): string {
  const first = os?.trim().split(/[\s,(/]+/)[0];
  return first || "Unknown";
}

const isBlank = (value: unknown) => value === null || value === undefined || value === "";

/** Number of the kind's spec columns that are empty for this entry. */
export const missingSpecCount = <T extends CatalogueItemBase>(
  item: T,
  kind: CatalogueKind<T> = PHONE_KIND as unknown as CatalogueKind<T>
) => kind.specKeys.filter((key) => isBlank((item as Record<string, unknown>)[key])).length;

const searchText = <T extends CatalogueItemBase>(item: T, kind: CatalogueKind<T>) =>
  [item.id, item.brand, item.modelName, item.status, ...kind.searchFields(item)]
    .filter((v) => v !== null && v !== undefined)
    .join(" ")
    .toLowerCase();

/** Distinct, sorted filter choices taken from the loaded catalogue. */
export function filterChoices<T extends CatalogueItemBase>(
  items: T[],
  kind: CatalogueKind<T> = PHONE_KIND as unknown as CatalogueKind<T>
) {
  const sorted = (values: Iterable<string>) =>
    [...new Set(values)].sort((a, b) => text(a, b));
  return {
    brands: sorted(items.map((i) => i.brand)),
    /** The kind's family choices: OS families, or GPU architectures. */
    osFamilies: sorted(items.map((i) => kind.familyOf(i))),
  };
}

/** Entries matching every filter; each search word must appear somewhere. */
export function applyFilters<T extends CatalogueItemBase>(
  items: T[],
  filters: CatalogueFilters,
  kind: CatalogueKind<T> = PHONE_KIND as unknown as CatalogueKind<T>
): T[] {
  const words = filters.query.toLowerCase().split(/\s+/).filter(Boolean);
  return items.filter((item) => {
    if (filters.brand && item.brand !== filters.brand) return false;
    if (filters.status && item.status !== filters.status) return false;
    if (filters.osFamily && kind.familyOf(item) !== filters.osFamily) return false;
    if (filters.specs === "complete" && missingSpecCount(item, kind) > 0) return false;
    if (filters.specs === "missing" && missingSpecCount(item, kind) === 0) return false;
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
    const haystack = searchText(item, kind);
    return words.every((word) => haystack.includes(word));
  });
}

export function sortItems<T extends CatalogueItemBase>(
  items: T[],
  sort: string,
  kind: CatalogueKind<T> = PHONE_KIND as unknown as CatalogueKind<T>
) {
  const option = kind.sortOptions.find((o) => o.value === sort) ?? kind.sortOptions[0];
  return [...items].sort((a, b) => option.compare(a, b));
}

/** "SGD 849.00", or null when the entry has no price history. */
export const catalogueItemPrice = (item: CatalogueItemBase) =>
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

/** GPU spec fields the admin form edits, in display order (the mapped `gpu` columns). */
export const GPU_SPEC_FIELDS = [
  { key: "baseClockMhz", label: "Base clock", unit: "MHz", numeric: true },
  { key: "boostClockMhz", label: "Boost clock", unit: "MHz", numeric: true },
  { key: "vramGb", label: "VRAM", unit: "GB", numeric: true },
  { key: "memoryType", label: "Memory type", unit: "", numeric: false },
  { key: "memoryBusBits", label: "Memory bus", unit: "bit", numeric: true },
  { key: "tdpWatts", label: "TDP", unit: "W", numeric: true },
  { key: "architecture", label: "Architecture", unit: "", numeric: false },
] as const;

export type GpuSpecKey = (typeof GPU_SPEC_FIELDS)[number]["key"];
export type GpuSpecFormValues = Record<GpuSpecKey, string>;

/** One price row of the Add form; storage/RAM are only offered for smartphones. */
export type PriceRow = {
  observedOn: string;
  price: string;
  currency: string;
  storageGb: string;
  ramGb: string;
  source: string;
};

/** One benchmark row of the Add form (smartphones only). */
export type BenchmarkRow = {
  benchmarkName: string;
  score: string;
  observedOn: string;
  source: string;
};

/** Today in the admin's own time zone, as `YYYY-MM-DD`. */
export function todayIso(now = new Date()) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export const emptyPriceRow = (): PriceRow => ({
  observedOn: todayIso(),
  price: "",
  currency: DEFAULT_CURRENCY,
  storageGb: "",
  ramGb: "",
  source: "",
});

export const emptyBenchmarkRow = (): BenchmarkRow => ({
  benchmarkName: "",
  score: "",
  observedOn: todayIso(),
  source: "",
});

/** A row the admin never typed into; the prefilled date and currency don't count. */
const untouchedPrice = (r: PriceRow) =>
  ![r.price, r.storageGb, r.ramGb, r.source].some((v) => v.trim());
const untouchedBenchmark = (r: BenchmarkRow) =>
  ![r.benchmarkName, r.score, r.source].some((v) => v.trim());

const positive = (text: string) => {
  const n = Number(text.trim());
  return text.trim() !== "" && Number.isFinite(n) && n > 0;
};
const wholePositive = (text: string) => positive(text) && Number.isInteger(Number(text.trim()));

/**
 * The first problem with the price and benchmark rows, or null. Untouched
 * rows are ignored; a started row needs every required field.
 */
export function observationError(values: CatalogueFormValues): string | null {
  const latest = todayIso(new Date(Date.now() + 86400000));
  for (const [i, row] of values.prices.entries()) {
    if (untouchedPrice(row)) continue;
    const at = `Price ${i + 1}`;
    if (!row.observedOn) return `${at}: enter the observed date.`;
    if (row.observedOn > latest) return `${at}: the observed date cannot be in the future.`;
    if (!positive(row.price)) return `${at}: enter a price above 0.`;
    for (const [label, text] of [["Storage", row.storageGb], ["RAM", row.ramGb]] as const) {
      if (text.trim() && !wholePositive(text)) return `${at}: ${label} must be a whole number of GB.`;
    }
  }
  for (const [i, row] of values.benchmarks.entries()) {
    if (untouchedBenchmark(row)) continue;
    const at = `Benchmark ${i + 1}`;
    if (!row.benchmarkName.trim()) return `${at}: enter the benchmark name.`;
    if (!positive(row.score)) return `${at}: enter a score above 0.`;
    if (!row.observedOn) return `${at}: enter the observed date.`;
    if (row.observedOn > latest) return `${at}: the observed date cannot be in the future.`;
  }
  return null;
}

const blankToNull = (text: string) => text.trim() || null;
const intOrNull = (text: string) => (text.trim() ? Number(text.trim()) : null);

function priceRequests(rows: PriceRow[]): SmartphonePriceRequest[] {
  return rows.filter((r) => !untouchedPrice(r)).map((r) => ({
    observedOn: r.observedOn,
    price: Number(r.price.trim()),
    currency: r.currency,
    storageGb: intOrNull(r.storageGb),
    ramGb: intOrNull(r.ramGb),
    source: blankToNull(r.source),
  }));
}

function benchmarkRequests(rows: BenchmarkRow[]): CatalogueBenchmarkRequest[] {
  return rows.filter((r) => !untouchedBenchmark(r)).map((r) => ({
    benchmarkName: r.benchmarkName.trim(),
    score: Number(r.score.trim()),
    observedOn: r.observedOn,
    source: blankToNull(r.source),
  }));
}

/** `{ [key]: list }` when the list has rows, else nothing, so updates never send observations. */
const ifAny = <K extends string, V>(key: K, list: V[]) =>
  (list.length > 0 ? { [key]: list } : {}) as Partial<Record<K, V[]>>;

/** What the add/edit form holds; inputs work in strings. `specs` is keyed by the kind's spec fields. */
export type CatalogueFormValues = {
  brand: string;
  modelName: string;
  releaseDate: string;
  status: string;
  /** `SpecFormValues` for smartphones, `GpuSpecFormValues` for GPUs. */
  specs: Record<string, string>;
  /** Prices to record with a new entry; always empty when editing. */
  prices: PriceRow[];
  /** Benchmarks to record with a new smartphone; always empty otherwise. */
  benchmarks: BenchmarkRow[];
};

export function itemToForm(item?: SmartphoneCatalogueItem): CatalogueFormValues {
  return {
    brand: item?.brand ?? "",
    modelName: item?.modelName ?? "",
    releaseDate: item?.releaseDate ?? "",
    status: item?.status ?? "VERIFIED",
    specs: specsToForm(item ? catalogueSpecs(item) : undefined),
    prices: item ? [] : [emptyPriceRow()],
    benchmarks: item ? [] : [emptyBenchmarkRow()],
  };
}

/** The request body; blank specs are sent as null so a PUT clears them. */
export function formToRequest(values: CatalogueFormValues): SmartphoneCatalogueRequest {
  const filled = formToSpecs(values.specs as SpecFormValues);
  const specs = Object.fromEntries(
    PHONE_SPEC_KEYS.map((key) => [key, filled[key] ?? null])
  ) as Pick<SmartphoneCatalogueRequest, (typeof PHONE_SPEC_KEYS)[number]>;
  return {
    brand: values.brand.trim(),
    modelName: values.modelName.trim(),
    releaseDate: values.releaseDate || null,
    status: values.status,
    ...specs,
    ...ifAny("prices", priceRequests(values.prices)),
    ...ifAny("benchmarks", benchmarkRequests(values.benchmarks)),
  };
}

function gpuItemToForm(item?: GpuCatalogueItem): CatalogueFormValues {
  return {
    brand: item?.brand ?? "",
    modelName: item?.modelName ?? "",
    releaseDate: item?.releaseDate ?? "",
    status: item?.status ?? "VERIFIED",
    specs: Object.fromEntries(
      GPU_SPEC_FIELDS.map(({ key }) => [key, item?.[key] == null ? "" : String(item[key])])
    ) as GpuSpecFormValues,
    prices: item ? [] : [emptyPriceRow()],
    benchmarks: [],
  };
}

/** The GPU request body; blank specs are sent as null so a PUT clears them. */
export function gpuFormToRequest(values: CatalogueFormValues): GpuCatalogueRequest {
  const specs = values.specs as GpuSpecFormValues;
  const field = (key: GpuSpecKey, numeric: boolean) => {
    const raw = (specs[key] ?? "").trim();
    if (!raw) return null;
    return numeric ? Number(raw) : raw;
  };
  // GPU prices are model-level: the form offers no variant, so none is sent.
  const prices = priceRequests(values.prices).map((p) => ({
    observedOn: p.observedOn,
    price: p.price,
    currency: p.currency,
    source: p.source,
  }));
  return {
    brand: values.brand.trim(),
    modelName: values.modelName.trim(),
    releaseDate: values.releaseDate || null,
    status: values.status,
    ...Object.fromEntries(GPU_SPEC_FIELDS.map(({ key, numeric }) => [key, field(key, numeric)])),
    ...ifAny("prices", prices),
  } as GpuCatalogueRequest;
}

/** A spec column of the catalogue table. */
type CatalogueColumn<T> = { label: string; unit?: string; value(item: T): string | number | null };

/** Everything the admin catalogue page needs to know about one kind of product. */
export type CatalogueKind<T extends CatalogueItemBase> = {
  key: "smartphones" | "gpus";
  /** "smartphone" / "GPU", for labels such as "Add GPU". */
  noun: string;
  title: string;
  searchPlaceholder: string;
  familyLabel: string;
  anyFamily: string;
  emptyHint: string;
  /** Read-only price and benchmark history exists only for smartphones. */
  hasHistory: boolean;
  /** The Add form's price rows offer a storage/RAM variant (smartphones). */
  priceVariants: boolean;
  /** The Add form offers benchmark rows (smartphones). */
  benchmarkEntry: boolean;
  /** Columns counted by the "Specifications" filter and the "n specs missing" note. */
  specKeys: readonly string[];
  sortOptions: SortOption<T>[];
  columns: CatalogueColumn<T>[];
  familyOf(item: T): string;
  searchFields(item: T): (string | number | null)[];
  // API calls are wrapped so the module is only touched when a call is made.
  list(): Promise<T[]>;
  create(body: unknown): Promise<T>;
  update(id: number, body: unknown): Promise<T>;
  remove(id: number): Promise<void>;
  toForm(item?: T): CatalogueFormValues;
  toRequest(values: CatalogueFormValues): unknown;
};

export const PHONE_KIND: CatalogueKind<SmartphoneCatalogueItem> = {
  key: "smartphones",
  noun: "smartphone",
  title: "Smartphone catalogue",
  searchPlaceholder: "Brand, model, chipset, OS or ID",
  familyLabel: "Operating system",
  anyFamily: "Any OS",
  emptyHint: "Add a smartphone, or run MobileAPI ingestion to populate it.",
  hasHistory: true,
  priceVariants: true,
  benchmarkEntry: true,
  specKeys: PHONE_SPEC_KEYS,
  sortOptions: SORT_OPTIONS,
  columns: [
    { label: "Chipset", value: (i) => i.chipset },
    { label: "RAM", unit: " GB", value: (i) => i.ramGb },
    { label: "Storage", unit: " GB", value: (i) => i.storageGb },
    { label: "Battery", unit: " mAh", value: (i) => i.batteryMah },
    { label: "OS", value: (i) => i.os },
  ],
  familyOf: (i) => osFamily(i.os),
  searchFields: (i) => [i.chipset, i.os, i.ipRating],
  list: () => listSmartphones(),
  create: (body) => createSmartphone(body as SmartphoneCatalogueRequest),
  update: (id, body) => updateSmartphone(id, body as SmartphoneCatalogueRequest),
  remove: (id) => deleteSmartphone(id),
  toForm: (item) => itemToForm(item),
  toRequest: (values) => formToRequest(values),
};

export const GPU_KIND: CatalogueKind<GpuCatalogueItem> = {
  key: "gpus",
  noun: "GPU",
  title: "GPU catalogue",
  searchPlaceholder: "Brand, model, architecture, memory or ID",
  familyLabel: "Architecture",
  anyFamily: "Any architecture",
  emptyHint: "Add a GPU, or run the GPU seed or TechSpot ingestion to populate it.",
  hasHistory: false,
  priceVariants: false,
  benchmarkEntry: false,
  specKeys: GPU_SPEC_FIELDS.map((f) => f.key),
  sortOptions: GPU_SORT_OPTIONS,
  columns: [
    { label: "Architecture", value: (i) => i.architecture },
    { label: "VRAM", unit: " GB", value: (i) => (i.vramGb === null ? null : Number(i.vramGb)) },
    { label: "Memory", value: (i) => i.memoryType },
    { label: "Boost clock", unit: " MHz", value: (i) => i.boostClockMhz },
    { label: "TDP", unit: " W", value: (i) => i.tdpWatts },
  ],
  familyOf: (i) => i.architecture?.trim() || "Unknown",
  searchFields: (i) => [i.architecture, i.memoryType],
  list: () => listGpus(),
  create: (body) => createGpu(body as GpuCatalogueRequest),
  update: (id, body) => updateGpu(id, body as GpuCatalogueRequest),
  remove: (id) => deleteGpu(id),
  toForm: (item) => gpuItemToForm(item),
  toRequest: (values) => gpuFormToRequest(values),
};
