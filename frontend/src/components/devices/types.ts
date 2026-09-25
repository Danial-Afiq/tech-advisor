import type { PhoneSpecs } from "./phoneSpecs";

export type Condition = "Excellent" | "Good" | "Fair" | "Poor";

export type UpgradePreferences = {
  budget: number;
  priorities: string[];
  urgency: string;
  brandFlex: string;
  painPoints: string;
  notes: string;
};

/**
 * An owned device as the UI renders it. Build one from API data with
 * `deviceFromApi` (deviceApi.ts). Optional fields are hidden on the card
 * when missing rather than filled with placeholder values.
 */
export type Device = {
  id: string;
  /** `user_devices.product_id`; null when not linked to the catalogue. */
  productId?: number | null;
  type: string;
  brand: string;
  model: string;
  image?: string;
  condition?: Condition;
  primary: boolean;
  use: string;
  purchaseDate: string;
  /** 0–100 (`user_devices.satisfaction_score`). */
  satisfaction?: number;
  upgradePreferences: UpgradePreferences;
  /** Phones only: catalogue specs with the user's overrides applied. */
  specs?: PhoneSpecs;
};
