import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearNotifications,
  deleteNotification,
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
 * A backend without the notification routes answers them with 401 (unknown
 * routes hit its non-public error page). The bell calls the unread count on
 * every page load, so a 401 here must never sign the user out.
 */
describe("notification API on a server without notification routes", () => {
  it.each([
    ["unread count", () => getUnreadNotificationCount()],
    ["list", () => listNotifications(0)],
    ["mark read", () => markNotificationRead(1)],
    ["mark all read", () => markAllNotificationsRead()],
    ["delete one", () => deleteNotification(1)],
    ["clear all", () => clearNotifications()],
  ])("%s: a 401 keeps the session and reads as unavailable", async (_name, call) => {
    setSession({ token: "valid", email: "a@b.com", role: "USER" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    const error = await call().then(() => null, (err: unknown) => err);

    expect(isNotificationsUnavailable(error)).toBe(true);
    expect(getSession()).toMatchObject({ token: "valid" });
  });
});

describe("listNotifications", () => {
  it("requests the page and size", async () => {
    setSession({ token: "valid", email: "a@b.com", role: "USER" });
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify({ items: [], page: 2, size: 10, totalItems: 0, totalPages: 0 })))
    );
    vi.stubGlobal("fetch", fetchMock);

    await listNotifications(2);

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/api\/notifications\?page=2&size=10$/);
  });
});
