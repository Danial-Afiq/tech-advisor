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
