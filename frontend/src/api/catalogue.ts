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
};

/** `GET /api/catalogue/smartphones` — every smartphone, ordered by brand and model. */
export const listSmartphones = () =>
  apiFetch<SmartphoneCatalogueItem[]>("/api/catalogue/smartphones");

/**
 * Body of the ADMIN create/replace routes (backend
 * `dto/SmartphoneCatalogueRequest.java`). Every spec is optional; `status`
 * must be VERIFIED or UNVERIFIED and `category` is always SMARTPHONE.
 */
export type SmartphoneCatalogueRequest = Omit<SmartphoneCatalogueItem, "id">;

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
