import { describe, expect, it } from "vitest";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import {
  EMPTY_FILTERS,
  applyFilters,
  boundsForRange,
  catalogueItemPrice,
  filterChoices,
  formToRequest,
  itemToForm,
  missingSpecCount,
  osFamily,
  pageButtons,
  pageCount,
  pageOf,
  priceRangeValue,
  sortItems,
} from "./catalogueAdmin";

const phone = (patch: Partial<SmartphoneCatalogueItem>): SmartphoneCatalogueItem => ({
  id: 1,
  brand: "Apple",
  modelName: "iPhone 15",
  releaseDate: "2023-09-22",
  status: "VERIFIED",
  chipset: "A16 Bionic",
  ramGb: 6,
  cpuGhz: "3.46",
  storageGb: 128,
  batteryMah: 3349,
  wiredChargingWatts: 20,
  wirelessChargingWatts: 15,
  displaySizeInches: "6.1",
  refreshRateHz: 60,
  weightG: 171,
  cameraSpecs: "48 MP",
  pixelDensity: 460,
  ipRating: "IP68",
  os: "iOS 17",
  softwareSupportYears: 6,
  latestPrice: null,
  currency: null,
  ...patch,
});

const iphone = phone({});
const galaxy = phone({
  id: 2,
  brand: "Samsung",
  modelName: "Galaxy S24",
  releaseDate: "2024-01-31",
  status: "UNVERIFIED",
  chipset: "Exynos 2400",
  ramGb: 8,
  batteryMah: 4000,
  os: "Android 14, One UI 6.1",
  cameraSpecs: null,
});
const pixel = phone({
  id: 3,
  brand: "Google",
  modelName: "Pixel 8a",
  releaseDate: null,
  chipset: "Tensor G3",
  ramGb: null,
  batteryMah: 4492,
  os: "Android 14",
});
const items = [iphone, galaxy, pixel];

describe("catalogue filters", () => {
  it("matches every search word against brand, model, chipset and OS", () => {
    expect(applyFilters(items, { ...EMPTY_FILTERS, query: "galaxy exynos" })).toEqual([galaxy]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, query: "android" })).toEqual([galaxy, pixel]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, query: "nokia" })).toEqual([]);
  });

  it("filters by brand, status, OS family and missing specs", () => {
    expect(applyFilters(items, { ...EMPTY_FILTERS, brand: "Google" })).toEqual([pixel]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, status: "UNVERIFIED" })).toEqual([galaxy]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, osFamily: "iOS" })).toEqual([iphone]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, specs: "missing" })).toEqual([galaxy, pixel]);
    expect(applyFilters(items, { ...EMPTY_FILTERS, specs: "complete" })).toEqual([iphone]);
  });

  it("offers distinct, sorted brand and OS-family choices", () => {
    expect(filterChoices(items)).toEqual({
      brands: ["Apple", "Google", "Samsung"],
      osFamilies: ["Android", "iOS"],
    });
    expect(osFamily(null)).toBe("Unknown");
    expect(missingSpecCount(galaxy)).toBe(1);
  });
});

describe("catalogue sorting", () => {
  const ids = (sort: string) => sortItems(items, sort).map((i) => i.id);

  it("sorts by name, release and specs with unknown values last", () => {
    expect(ids("brand-asc")).toEqual([1, 3, 2]);
    expect(ids("brand-desc")).toEqual([2, 3, 1]);
    expect(ids("release-desc")).toEqual([2, 1, 3]);
    expect(ids("release-asc")).toEqual([1, 2, 3]);
    expect(ids("ram-desc")).toEqual([2, 1, 3]);
    expect(ids("battery-desc")).toEqual([3, 2, 1]);
    expect(ids("id-desc")).toEqual([3, 2, 1]);
  });

  it("does not reorder the input array", () => {
    sortItems(items, "brand-desc");
    expect(items.map((i) => i.id)).toEqual([1, 2, 3]);
  });
});

describe("catalogue paging", () => {
  it("slices a page and clamps an out-of-range page", () => {
    const list = Array.from({ length: 55 }, (_, i) => i);
    expect(pageCount(55, 25)).toBe(3);
    expect(pageCount(0, 25)).toBe(1);
    expect(pageOf(list, 3, 25)).toEqual({ page: 3, rows: [50, 51, 52, 53, 54] });
    expect(pageOf(list, 9, 25).page).toBe(3);
  });

  it("shows the first, last and nearby pages with gaps", () => {
    expect(pageButtons(1, 1)).toEqual([1]);
    expect(pageButtons(2, 5)).toEqual([1, 2, 3, 4, 5]);
    expect(pageButtons(6, 30)).toEqual([1, "…", 4, 5, 6, 7, 8, "…", 30]);
    expect(pageButtons(30, 30)).toEqual([1, "…", 28, 29, 30]);
  });
});

describe("catalogue form", () => {
  it("round-trips an entry and sends blank specs as null", () => {
    const values = itemToForm(galaxy);
    values.specs.chipset = "  ";
    values.brand = "  Samsung ";
    const body = formToRequest(values);
    expect(body.brand).toBe("Samsung");
    expect(body.chipset).toBeNull();
    expect(body.cameraSpecs).toBeNull();
    expect(body.cpuGhz).toBe(3.46);
    expect(body.os).toBe("Android 14, One UI 6.1");
    expect("id" in body).toBe(false);
  });

  it("starts a new entry as VERIFIED with no release date", () => {
    const body = formToRequest({ ...itemToForm(), brand: "Nothing", modelName: "Phone (3)" });
    expect(body.status).toBe("VERIFIED");
    expect(body.releaseDate).toBeNull();
    expect(body.ramGb).toBeNull();
  });
});

describe("catalogue prices", () => {
  const cheap = phone({ id: 11, latestPrice: 499, currency: "SGD" });
  const dear = phone({ id: 12, latestPrice: "1299.50", currency: "SGD" });
  const none = phone({ id: 13 });
  const priced = [none, dear, cheap];

  it("sorts by price either way with unpriced entries last", () => {
    expect(sortItems(priced, "price-asc").map((i) => i.id)).toEqual([11, 12, 13]);
    expect(sortItems(priced, "price-desc").map((i) => i.id)).toEqual([12, 11, 13]);
  });

  it("filters entries with and without a price", () => {
    expect(applyFilters(priced, { ...EMPTY_FILTERS, price: "unpriced" })).toEqual([none]);
    expect(applyFilters(priced, { ...EMPTY_FILTERS, price: "priced" })).toEqual([dear, cheap]);
  });

  it("formats the price like recommendation cards, or null without one", () => {
    expect(catalogueItemPrice(dear)?.replace(/\u00a0/g, " ")).toBe("SGD 1,299.50");
    expect(catalogueItemPrice(none)).toBeNull();
  });
});

describe("catalogue price ranges", () => {
  const at = (id: number, latestPrice: number | null) => phone({ id, latestPrice, currency: "SGD" });
  const list = [at(1, 300), at(2, 500), at(3, 999), at(4, 2400), at(5, null)];
  const ids = (minPrice: string, maxPrice: string) =>
    applyFilters(list, { ...EMPTY_FILTERS, minPrice, maxPrice }).map((i) => i.id);

  it("keeps priced entries within inclusive bounds", () => {
    expect(ids("0", "500")).toEqual([1, 2]);
    expect(ids("500", "1000")).toEqual([2, 3]);
    expect(ids("2000", "")).toEqual([4]);
    expect(ids("", "400")).toEqual([1]);
    expect(ids("", "")).toEqual([1, 2, 3, 4, 5]);
    expect(ids("abc", "")).toEqual([1, 2, 3, 4, 5]);
  });

  it("maps presets to editable bounds and back", () => {
    const blank = { minPrice: "", maxPrice: "" };
    expect(boundsForRange("500-1000", blank)).toEqual({ minPrice: "500", maxPrice: "1000" });
    expect(boundsForRange("2000-", blank)).toEqual({ minPrice: "2000", maxPrice: "" });
    expect(boundsForRange("", { minPrice: "1", maxPrice: "2" })).toEqual(blank);
    expect(boundsForRange("custom", { minPrice: "1", maxPrice: "2" })).toEqual({ minPrice: "1", maxPrice: "2" });

    expect(priceRangeValue(EMPTY_FILTERS)).toBe("");
    expect(priceRangeValue({ ...EMPTY_FILTERS, minPrice: "500", maxPrice: "1000" })).toBe("500-1000");
    expect(priceRangeValue({ ...EMPTY_FILTERS, minPrice: "650", maxPrice: "1000" })).toBe("custom");
  });
});
