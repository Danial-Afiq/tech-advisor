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
  budget: null,
  currency: null,
  upgradeUrgency: null,
  brandFlexibility: null,
  priorities: null,
  painPoints: null,
  notes: null,
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
      budget: null,
      currency: null,
    });
  });

  it("reads the saved budget and sends it back with its currency", () => {
    const device = deviceFromApi({
      ...base, budget: 1500, currency: "USD", priorities: '{"camera":4}',
    });

    // Every persisted preference comes from the response.
    expect(device.upgradePreferences).toMatchObject({
      budget: 1500,
      currency: "USD",
      priorities: ["Camera"],
    });
    expect(deviceToRequest(device)).toMatchObject({ budget: 1500, currency: "USD" });
  });

  it("accepts a NUMERIC budget that arrives as a string", () => {
    const device = deviceFromApi({
      ...base,
      budget: "1200.00" as unknown as number,
      currency: "SGD",
    });
    expect(device.upgradePreferences.budget).toBe(1200);
  });

  it("has no budget when none is saved, and doesn't invent one", () => {
    const device = deviceFromApi(base);
    expect(device.upgradePreferences.budget).toBeNull();
    expect(device.upgradePreferences.currency).toBeNull();
    expect(deviceToRequest(device)).toMatchObject({ budget: null, currency: null });
  });

  it("defaults the currency to SGD when a new budget is sent", () => {
    const device = deviceFromApi(base);
    const request = deviceToRequest({
      ...device,
      upgradePreferences: { ...device.upgradePreferences, budget: 800 },
    });
    expect(request).toMatchObject({ budget: 800, currency: "SGD" });
  });

  it("round-trips canonical preferences without losing weighted priorities or pain-point arrays", () => {
    const device = deviceFromApi({
      ...base,
      budget: 1234.56,
      currency: "USD",
      upgradeUrgency: "URGENT",
      brandFlexibility: "SOMEWHAT_FLEXIBLE",
      priorities: '{"battery":5,"camera":4,"software_support":2}',
      painPoints: '["Battery drains quickly","Camera struggles at night"]',
      notes: "Keep it for four years.\nAvoid launch pricing.",
    });
    expect(device.upgradePreferences).toMatchObject({
      priorities: ["Battery life", "Camera", "Software support"],
      urgency: "URGENT",
      brandFlex: "SOMEWHAT_FLEXIBLE",
      painPoints: "Battery drains quickly; Camera struggles at night",
      notes: "Keep it for four years.\nAvoid launch pricing.",
    });
    const request = deviceToRequest(device);
    expect(JSON.parse(request.priorities!)).toEqual({ battery: 5, camera: 4, software_support: 2 });
    expect(JSON.parse(request.painPoints!)).toEqual(["Battery drains quickly", "Camera struggles at night"]);
    expect(request).toMatchObject({
      budget: 1234.56, currency: "USD", upgradeUrgency: "URGENT",
      brandFlexibility: "SOMEWHAT_FLEXIBLE", notes: device.upgradePreferences.notes,
    });

    // Editing selection preserves existing weights, assigns new ones equally,
    // and turns edited prose into a valid JSON string.
    device.upgradePreferences.priorities = ["Camera", "Performance"];
    device.upgradePreferences.painPoints = 'A new "pain point"\nwith a newline';
    const edited = deviceToRequest(device);
    expect(JSON.parse(edited.priorities!)).toEqual({ camera: 4, performance: 1 });
    expect(JSON.parse(edited.painPoints!)).toBe(device.upgradePreferences.painPoints);
  });

  it("keeps an empty saved priority selection empty after a reload", () => {
    const device = deviceFromApi({ ...base, budget: 800, currency: "SGD", priorities: "{}" });
    expect(device.upgradePreferences.priorities).toEqual([]);
    expect(deviceToRequest(device).priorities).toBe("{}");
  });

  it.each(["{}", "null"])("normalizes legacy empty pain points %s before saving", (painPoints) => {
    const device = deviceFromApi({
      ...base, budget: 800, currency: "USD", priorities: "{}", painPoints,
    });
    expect(device.upgradePreferences.painPoints).toBe("");
    expect(deviceToRequest(device)).toMatchObject({
      budget: 800, currency: "USD", priorities: "{}", painPoints: '""',
    });
  });

  it("saves comma-separated use cases as distinct JSON array entries", () => {
    const device = deviceFromApi(base);
    expect(deviceToRequest({ ...device, use: " Gaming,  Photography, Social media ,, " }).useCases)
      .toBe('["Gaming","Photography","Social media"]');
    expect(deviceToRequest({ ...device, use: "   , ,  " }).useCases).toBe("[]");
    expect(deviceToRequest({ ...device, use: "Gaming" }).useCases).toBe('["Gaming"]');
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
