import type { Condition, UpgradePreferences } from "./types";

export const DEVICE_ICONS: Record<string, string> = {
  "Desktop PC": "🖥️",
  Laptop: "💻",
  Phone: "📱",
  Monitor: "🖼️",
  TV: "📺",
  Keyboard: "⌨️",
  Mouse: "🖱️",
  Tablet: "◫",
  // Unlinked devices come back without a stored type (see `deviceFromApi`).
  Other: "🔌",
};
export const DEVICE_TYPES = Object.keys(DEVICE_ICONS);
export const CONDITIONS: Condition[] = ["Excellent", "Good", "Fair", "Poor"];
/** Labels mapped to the existing Java/Python factor vocabulary. */
export const PRIORITY_FACTORS: Record<string, string> = {
  "Value for money": "value",
  Performance: "performance",
  Longevity: "longevity",
  "Battery life": "battery",
  Camera: "camera",
  Portability: "portability",
  "Display quality": "display",
  "Build quality": "build_quality",
  "Thermal management": "thermals",
  "Software support": "software_support",
  Connectivity: "connectivity",
  Audio: "audio",
};
export const PRIORITIES = Object.keys(PRIORITY_FACTORS);
export const URGENCIES = [
  { value: "", label: "Choose urgency" },
  { value: "NOT_URGENT", label: "Not urgent" },
  { value: "SOMEWHAT_URGENT", label: "Somewhat urgent" },
  { value: "URGENT", label: "Urgent" },
  { value: "CRITICAL", label: "Critical" },
];
export const BRAND_FLEX = [
  { value: "", label: "Choose brand flexibility" },
  { value: "EXTREMELY_FLEXIBLE", label: "Extremely flexible" },
  { value: "FLEXIBLE", label: "Flexible" },
  { value: "SOMEWHAT_FLEXIBLE", label: "Somewhat flexible" },
  { value: "NOT_FLEXIBLE", label: "Not flexible" },
];

export const deviceIcon = (type: string) => DEVICE_ICONS[type] ?? "🔌";

/** Where the budget slider starts for a device that has no saved budget. */
export const SUGGESTED_BUDGET = 1200;

/** The budget currency used when none is saved (backend default too). */
export const DEFAULT_CURRENCY = "SGD";

/**
 * Starting values for a device's upgrade profile. The budget is null: nothing
 * is saved until the user picks one in the upgrade-preferences modal.
 */
export const defaultPrefs = (): UpgradePreferences => ({
  budget: null,
  currency: null,
  priorities: ["Value for money", "Performance", "Longevity"],
  urgency: "NOT_URGENT",
  brandFlex: "FLEXIBLE",
  painPoints: "",
  notes: "",
});

/** Formats an amount; SGD (the default) shows as `S$1,000`, others as `USD 1,000`. */
export const money = (n: number, currency?: string | null) => {
  const amount = Number(n).toLocaleString();
  return !currency || currency === DEFAULT_CURRENCY
    ? `S$${amount}`
    : `${currency} ${amount}`;
};

/** A device's budget for display, or "No budget set". */
export const budgetLabel = (prefs: UpgradePreferences) =>
  prefs.budget == null ? "No budget set" : money(prefs.budget, prefs.currency);

export function formatDate(date: string) {
  if (!date) return "—";
  const d = new Date(date);
  if (Number.isNaN(d.getTime())) return date;
  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

export function ageLabel(date: string) {
  if (!date) return "Purchase date unknown";
  const years = Math.max(
    0,
    Math.floor((Date.now() - new Date(date).getTime()) / (365.25 * 86400000))
  );
  return years === 0
    ? "Less than 1 year old"
    : `${years} year${years > 1 ? "s" : ""} old`;
}
