import { apiFetch } from "./client";

/**
 * One item of `GET /api/catalogue/smartphones` (backend
 * `dto/SmartphoneCatalogueResponse.java`): a `products` row plus its `phone`
 * specs. BigDecimal columns may arrive as numbers or strings.
 */
export type SmartphoneCatalogueItem = {
  id: number;
  brand: string;
  modelName: string;
  releaseDate: string | null;
  status: string;
  chipset: string | null;
  ramGb: number | null;
  cpuGhz: number | string | null;
  storageGb: number | null;
  batteryMah: number | null;
  wiredChargingWatts: number | null;
  wirelessChargingWatts: number | null;
  displaySizeInches: number | string | null;
  refreshRateHz: number | null;
  weightG: number | null;
  cameraSpecs: string | null;
  pixelDensity: number | null;
  ipRating: string | null;
  os: string | null;
  softwareSupportYears: number | string | null;
  /** Latest `price_history` observation (same rule as recommendations), or null. */
  latestPrice: number | string | null;
  /** ISO 4217 code of `latestPrice`, or null. */
  currency: string | null;
};

/** `GET /api/catalogue/smartphones` — every smartphone, ordered by brand and model. */
export const listSmartphones = () =>
  apiFetch<SmartphoneCatalogueItem[]>("/api/catalogue/smartphones");

/**
 * Body of the ADMIN create/replace routes (backend
 * `dto/SmartphoneCatalogueRequest.java`). Every spec is optional; `status`
 * must be VERIFIED or UNVERIFIED and `category` is always SMARTPHONE. Prices
 * are read-only here; they come from `price_history`.
 */
export type SmartphoneCatalogueRequest = Omit<
  SmartphoneCatalogueItem,
  "id" | "latestPrice" | "currency"
>;

/** `POST /api/admin/catalogue/smartphones` — ADMIN only; 409 on a duplicate brand + model. */
export const createSmartphone = (body: SmartphoneCatalogueRequest) =>
  apiFetch<SmartphoneCatalogueItem>("/api/admin/catalogue/smartphones", {
    method: "POST",
    body,
  });

/** `PUT /api/admin/catalogue/smartphones/{id}` — replaces the products and phone rows. */
export const updateSmartphone = (id: number, body: SmartphoneCatalogueRequest) =>
  apiFetch<SmartphoneCatalogueItem>(`/api/admin/catalogue/smartphones/${id}`, {
    method: "PUT",
    body,
  });

/** `DELETE /api/admin/catalogue/smartphones/{id}` — deletes both rows together. */
export const deleteSmartphone = (id: number) =>
  apiFetch<void>(`/api/admin/catalogue/smartphones/${id}`, { method: "DELETE" });

/** One `price_history` row (backend `dto/CataloguePriceResponse.java`). */
export type CataloguePrice = {
  id: number;
  price: number | string;
  currency: string;
  source: string | null;
  observedAt: string;
  /** e.g. "256 GB · 8 GB RAM"; null for a model-level price. */
  variant: string | null;
};

/** One `benchmark_results` row (backend `dto/CatalogueBenchmarkResponse.java`). */
export type CatalogueBenchmark = {
  id: number;
  benchmarkName: string;
  score: number | string;
  unit: string | null;
  higherIsBetter: boolean;
  source: string | null;
  observedAt: string;
};

/** `GET /api/admin/catalogue/smartphones/{id}/prices` — ADMIN only, newest first, at most 50. */
export const listSmartphonePrices = (id: number) =>
  apiFetch<CataloguePrice[]>(`/api/admin/catalogue/smartphones/${id}/prices`);

/** `GET /api/admin/catalogue/smartphones/{id}/benchmarks` — ADMIN only, newest first, at most 50. */
export const listSmartphoneBenchmarks = (id: number) =>
  apiFetch<CatalogueBenchmark[]>(`/api/admin/catalogue/smartphones/${id}/benchmarks`);
