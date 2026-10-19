import { ApiError, apiFetch } from "./client";
import type {
  NotificationPage,
  NotificationQuery,
} from "../components/notifications/types";

/*
 * In-app notifications (ticket 4.4.2).
 *
 * PLANNED CONTRACT: these backend routes are not implemented yet. The shapes
 * below are what the frontend expects; frontend/AGENTS.md records them for the
 * backend work. Until then the backend answers these routes with 401 (unknown
 * routes fall through to its non-public error page), so every call here passes
 * `keepSessionOn401` - otherwise the bell would sign the user out on every page
 * load - and the UI shows notifications as unavailable.
 *
 *   GET /api/notifications?read=unread|read&tier=HIGH|MEDIUM|LOW&page=0&size=10
 *       -> NotificationPage, newest first, dismissed ones excluded
 *   GET /api/notifications/unread-count          -> { count: number }
 *   PUT /api/notifications/{id}/read             -> 204
 *   PUT /api/notifications/read-all              -> 204
 *   PUT /api/notifications/{id}/dismiss          -> 204 (hides it; the
 *       recommendation itself is not dismissed or deleted)
 *
 * All are scoped to the signed-in user; another user's id returns 404.
 */

export const NOTIFICATION_PAGE_SIZE = 10;

// See the note above: a 401 here must never end the user's session.
const OPTIONAL_FEATURE = { keepSessionOn401: true } as const;

export function listNotifications(query: NotificationQuery) {
  const params = new URLSearchParams({
    page: String(query.page),
    size: String(query.size),
  });

  if (query.read !== "all") {
    params.set("read", query.read);
  }

  if (query.tier) {
    params.set("tier", query.tier);
  }

  return apiFetch<NotificationPage>(`/api/notifications?${params}`, OPTIONAL_FEATURE);
}

export async function getUnreadNotificationCount() {
  const { count } = await apiFetch<{ count: number }>(
    "/api/notifications/unread-count",
    OPTIONAL_FEATURE
  );
  return count;
}

export function markNotificationRead(notificationId: number) {
  return apiFetch<void>(
    `/api/notifications/${encodeURIComponent(notificationId)}/read`,
    { ...OPTIONAL_FEATURE, method: "PUT" }
  );
}

export function markAllNotificationsRead() {
  return apiFetch<void>("/api/notifications/read-all", {
    ...OPTIONAL_FEATURE,
    method: "PUT",
  });
}

export function dismissNotification(notificationId: number) {
  return apiFetch<void>(
    `/api/notifications/${encodeURIComponent(notificationId)}/dismiss`,
    { ...OPTIONAL_FEATURE, method: "PUT" }
  );
}

/**
 * True when this server has no notification routes - the backend half of
 * ticket 4.4.2 is not deployed there. Today that arrives as 401 (see above);
 * 404/405 cover a server whose error page is public. A genuinely expired
 * session is still caught, and signed out, by the page's own requests.
 */
export function isNotificationsUnavailable(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 404 || error.status === 405)
  );
}
