import type {
  AppNotification,
  NotificationReadFilter,
  NotificationTier,
} from "./types";

/*
 * Display only. The tier itself is decided by the backend from the verdict and
 * evidence grade; never recompute it here.
 */
const TIER_PRESENTATIONS: Record<
  NotificationTier,
  { label: string; badgeClass: string }
> = {
  HIGH: { label: "High confidence", badgeClass: "badge-primary badge-outline" },
  MEDIUM: { label: "Medium confidence", badgeClass: "badge-warning badge-outline" },
  LOW: { label: "Low confidence", badgeClass: "badge-ghost" },
};

export function tierPresentation(tier: NotificationTier) {
  return TIER_PRESENTATIONS[tier];
}

export const READ_FILTER_OPTIONS = [
  { value: "all", label: "All" },
  { value: "unread", label: "Unread" },
  { value: "read", label: "Read" },
] as const satisfies readonly { value: NotificationReadFilter; label: string }[];

export const TIER_FILTER_OPTIONS = [
  { value: "", label: "All tiers" },
  { value: "HIGH", label: "High confidence" },
  { value: "MEDIUM", label: "Medium confidence" },
  { value: "LOW", label: "Low confidence" },
] as const;

export function parseTierFilter(value: string): NotificationTier | null {
  return value === "HIGH" || value === "MEDIUM" || value === "LOW" ? value : null;
}

export function notificationProductName(notification: AppNotification) {
  return [notification.candidateBrand, notification.candidateModelName]
    .filter(Boolean)
    .join(" ");
}

/** Query string the dashboard reads to scroll to and highlight one recommendation. */
export const RECOMMENDATION_QUERY_PARAM = "recommendation";
