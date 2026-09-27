import { describe, expect, it } from "vitest";
import {
  deviceFromApi,
  deviceToRequest,
  normalizePhoneSpecs,
} from "./deviceApi";
import type { DeviceResponse } from "./deviceApi";

const base: DeviceResponse = {
  id: 7,
  productId: 3,
  productBrand: "Google",
  productModelName: "Pixel 9a",
  customName: null,
  purchaseDate: "2025-04-10",
  condition: "fair",
  satisfactionScore: 80,
  useCases: '["photos", "gaming"]',
  specOverrides: "{}",
  current: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("normalizePhoneSpecs", () => {
  it("accepts snake_case, converts NUMERIC strings and drops unknown keys", () => {
    expect(
      normalizePhoneSpecs({
        battery_mah: 5100,
        display_size_inches: "6.3",
        ip_rating: "IP68",
        product_id: 3,
      })
    ).toEqual({ batteryMah: 5100, displaySizeInches: 6.3, ipRating: "IP68" });
  });
});

describe("deviceFromApi", () => {
  it("maps a smartphone response and applies spec overrides on top", () => {
    const device = deviceFromApi(
      { ...base, specOverrides: '{"storage_gb": 256}' },
      { category: "SMARTPHONE", phone: { storage_gb: 128, ram_gb: 8 } }
    );

    expect(device).toMatchObject({
      id: "7",
      type: "Phone",
      brand: "Google",
      model: "Pixel 9a",
      condition: "Fair",
      use: "photos, gaming",
      satisfaction: 80,
      specs: { storageGb: 256, ramGb: 8 },
    });
  });

  it("round-trips through deviceToRequest for an unlinked device", () => {
    const device = deviceFromApi({
      ...base,
      productId: null,
      productBrand: null,
      productModelName: null,
      customName: "Samsung Galaxy S21",
      useCases: '["photos and gaming"]',
    });

    expect(deviceToRequest(device)).toEqual({
      productId: null,
      customName: "Samsung Galaxy S21",
      purchaseDate: "2025-04-10",
      condition: "Fair",
      satisfactionScore: 80,
      useCases: '["photos and gaming"]',
      specOverrides: "{}",
    });
  });

  it("leaves unknown values empty instead of inventing them", () => {
    const device = deviceFromApi({
      ...base,
      productBrand: null,
      productModelName: null,
      customName: "Old phone",
      condition: "cracked",
      satisfactionScore: null,
    });

    expect(device.model).toBe("Old phone");
    expect(device.condition).toBeUndefined();
    expect(device.satisfaction).toBeUndefined();
    expect(device.specs).toBeUndefined();
  });
});
