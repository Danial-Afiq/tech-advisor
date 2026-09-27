import { deviceFromApi } from "./deviceApi";
import type { DeviceResponse } from "./deviceApi";
import type { Device } from "./types";

/** Front-end-only demo data from the interactive prototype. */

export const DEMO_USER = { name: "Alex Tan", email: "alex@example.com" };

/*
 * The phone is built the way real data will be: a `GET /api/devices` item
 * plus its catalogue `phone` row (snake_case, as stored in Postgres), run
 * through `deviceFromApi`. Swap these literals for fetched data.
 */
const demoPhoneResponse: DeviceResponse = {
  id: 1,
  productId: 101,
  productBrand: "Apple",
  productModelName: "iPhone 13 Pro Max",
  customName: null,
  purchaseDate: "2022-01-15",
  condition: "Good",
  satisfactionScore: 66,
  useCases: '["Messaging", "photos", "maps", "media and everyday use"]',
  specOverrides: "{}",
  current: true,
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
};

const demoPhoneRow = {
  product_id: 101,
  chipset: "Apple A15 Bionic",
  ram_gb: 6,
  cpu_ghz: "3.23",
  storage_gb: 128,
  battery_mah: 4352,
  wired_charging_watts: 27,
  wireless_charging_watts: 15,
  display_size_inches: "6.7",
  refresh_rate_hz: 120,
  weight_g: 240,
  camera_specs: "12 MP triple (wide, ultra-wide, telephoto)",
  pixel_density: 458,
  ip_rating: "IP68",
  os: "iOS",
  software_support_years: null,
};

export const DEMO_DEVICES: Device[] = [
  deviceFromApi(demoPhoneResponse, {
    category: "SMARTPHONE",
    phone: demoPhoneRow,
    primary: true,
    image:
      "https://mobilerelation.com.sg/wp-content/uploads/2025/10/iphone-13-pro-max-silver.webp",
    preferences: {
      budget: 1000,
      priorities: ["Battery life", "Camera", "Longevity"],
      urgency: "Only when worthwhile",
      brandFlex: "Prefer same brand",
      painPoints:
        "Battery life is the main reason I would consider replacing this phone.",
      notes:
        "Do not recommend an upgrade just for a small performance improvement.",
    },
  }),
  {
    id: "laptop-1",
    type: "Laptop",
    brand: "ASUS",
    model: "ROG Zephyrus G14",
    image:
      "https://dlcdnwebimgs.asus.com/gain/E0275281-F18B-42C3-A025-3331C35A888F",
    condition: "Good",
    primary: false,
    use: "University work, coding, VMs and occasional gaming",
    purchaseDate: "2023-05-10",
    satisfaction: 74,
    upgradePreferences: {
      budget: 2000,
      priorities: ["Performance", "Battery life", "Portability"],
      urgency: "Only if current device struggles",
      brandFlex: "Open to alternatives",
      painPoints:
        "Heavy VM workloads and build times matter more than gaming FPS.",
      notes:
        "Prefer 32 GB RAM or an easy upgrade path. Avoid very heavy laptops.",
    },
  },
];

const SCAN_OUTCOMES = [
  { outcome: "WAIT", title: "Wait for prices to settle" },
  { outcome: "UPGRADE", title: "Upgrade now: your threshold was reached" },
  { outcome: "HOLD", title: "Hold: this device is still a good fit" },
  { outcome: "SKIP", title: "Skip this release" },
];

/** Stand-in for a real recommendation until the page is wired to the API. */
export const randomScanOutcome = () =>
  SCAN_OUTCOMES[Math.floor(Math.random() * SCAN_OUTCOMES.length)];
