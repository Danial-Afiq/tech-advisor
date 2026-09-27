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

export const defaultPrefs = (): UpgradePreferences => ({
  budget: 1200,
  priorities: ["Value for money", "Performance", "Longevity"],
  urgency: "Only when worthwhile",
  brandFlex: "Open to alternatives",
  painPoints: "",
  notes: "",
});

export const money = (n: number) => `S$${Number(n).toLocaleString()}`;

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
