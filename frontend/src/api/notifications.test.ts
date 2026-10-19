import { afterEach, describe, expect, it, vi } from "vitest";
import {
  dismissNotification,
  getUnreadNotificationCount,
  isNotificationsUnavailable,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "./notifications";
import { getSession, setSession } from "./session";

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

/*
 * Until the notification backend exists, the real server answers these routes
 * with 401 (unknown routes hit its non-public error page). The bell calls the
 * unread count on every page load, so a 401 here must never sign the user out.
 */
describe("notification API before the backend exists", () => {
  it.each([
    ["unread count", () => getUnreadNotificationCount()],
    ["list", () => listNotifications({ read: "all", tier: null, page: 0, size: 10 })],
    ["mark read", () => markNotificationRead(1)],
    ["mark all read", () => markAllNotificationsRead()],
    ["dismiss", () => dismissNotification(1)],
  ])("%s: a 401 keeps the session and reads as unavailable", async (_name, call) => {
    setSession({ token: "valid", email: "a@b.com", role: "USER" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    const error = await call().then(() => null, (err: unknown) => err);

    expect(isNotificationsUnavailable(error)).toBe(true);
    expect(getSession()).toMatchObject({ token: "valid" });
  });
});

describe("listNotifications", () => {
  it("sends read and tier filters only when set", async () => {
    setSession({ token: "valid", email: "a@b.com", role: "USER" });
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 })))
    );
    vi.stubGlobal("fetch", fetchMock);

    await listNotifications({ read: "all", tier: null, page: 0, size: 10 });
    await listNotifications({ read: "unread", tier: "HIGH", page: 2, size: 10 });

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/notifications\?page=0&size=10$/);
    expect(fetchMock.mock.calls[1][0]).toMatch(/\/api\/notifications\?page=2&size=10&read=unread&tier=HIGH$/);
  });
});
