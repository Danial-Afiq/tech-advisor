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
};
export const DEVICE_TYPES = Object.keys(DEVICE_ICONS);
export const CONDITIONS: Condition[] = ["Excellent", "Good", "Fair", "Poor"];
export const PRIORITIES = [
  "Value for money",
  "Performance",
  "Longevity",
  "Battery life",
  "Camera",
  "Portability",
  "Quiet operation",
  "Display quality",
];
export const URGENCIES = [
  "Only when worthwhile",
  "I like staying current",
  "Only if current device struggles",
  "Replace only when necessary",
];
export const BRAND_FLEX = [
  "Open to alternatives",
  "Prefer same brand",
  "Same ecosystem if possible",
  "No preference",
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
  urgency: "Only when worthwhile",
  brandFlex: "Open to alternatives",
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
