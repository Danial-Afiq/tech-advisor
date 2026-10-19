import { ApiError, apiFetch } from "./client";
import type { NotificationPage } from "../components/notifications/types";

/*
 * In-app notifications (ticket 4.4.2).
 *
 *   GET /api/notifications?page=0&size=10   -> NotificationPage, newest first
 *   GET /api/notifications/unread-count     -> { count: number }
 *   PUT /api/notifications/{id}/read        -> 204
 *   PUT /api/notifications/read-all         -> 204
 *
 * All are scoped to the signed-in user; another user's id returns 404.
 *
 * Every call passes `keepSessionOn401`: a backend without these routes answers
 * them with 401 (unknown routes fall through to its non-public error page),
 * e.g. while the frontend is deployed ahead of the backend. The bell checks
 * the unread count on every page, so that must not sign the user out; the
 * page's own requests still catch a genuinely expired session.
 */

export const NOTIFICATION_PAGE_SIZE = 10;

const OPTIONAL_FEATURE = { keepSessionOn401: true } as const;

export function listNotifications(page: number, size = NOTIFICATION_PAGE_SIZE) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
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

/** True when this server has no notification routes (see the note above). */
export function isNotificationsUnavailable(error: unknown) {
  return (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 404 || error.status === 405)
  );
}
