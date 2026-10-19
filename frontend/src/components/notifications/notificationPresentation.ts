import { formatDate } from "../devices/deviceOptions";

/** Query string the dashboard reads to scroll to and highlight one recommendation. */
export const RECOMMENDATION_QUERY_PARAM = "recommendation";

/** How often the bell re-checks the unread count while a page is open. */
export const NOTIFICATION_POLL_MS = 45_000;

/** "Just now", "5m ago", "3h ago", "2d ago", then a date. */
export function relativeTime(createdAt: string, now = Date.now()) {
  const minutes = Math.floor((now - new Date(createdAt).getTime()) / 60_000);
  if (Number.isNaN(minutes)) return createdAt;
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return formatDate(createdAt);
}
