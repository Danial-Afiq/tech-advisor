import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client";
import { NotificationCenter } from "./NotificationCenter";
import { NOTIFICATION_POLL_MS, relativeTime } from "./notificationPresentation";
import type { AppNotification, NotificationPage } from "./types";

const mocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  getUnreadNotificationCount: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
}));

vi.mock("../../api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api/notifications")>()),
  ...mocks,
}));

const grouped: AppNotification = {
  notificationId: 1,
  type: "NEW_RECOMMENDATION",
  title: "New upgrade recommendations!",
  message: "We found 3 new upgrade options for your iPhone 13. See what's worth upgrading to.",
  recommendationId: 42,
  available: true,
  read: false,
  createdAt: new Date().toISOString(),
};

const older: AppNotification = {
  ...grouped,
  notificationId: 2,
  title: "New upgrade recommendation!",
  message: "Google Pixel 11 is worth watching for your iPhone 13.",
  recommendationId: 43,
  read: true,
  createdAt: "2026-10-01T09:00:00Z",
};

function pageOf(items: AppNotification[], page = 0, totalPages = 1): NotificationPage {
  return { items, page, size: 10, totalItems: items.length, totalPages };
}

function Location() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname + location.search}</span>;
}

function renderCenter() {
  render(
    <MemoryRouter initialEntries={["/devices"]}>
      <Location />
      <Routes>
        <Route path="*" element={<NotificationCenter />} />
      </Routes>
    </MemoryRouter>
  );
  return userEvent.setup();
}

async function openPanel(user: ReturnType<typeof userEvent.setup>) {
  await user.click(bell());
  return within(screen.getByRole("dialog", { name: "Notifications" }));
}

const bell = () => screen.getByRole("button", { name: /^Notifications/ });

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getUnreadNotificationCount.mockResolvedValue(1);
  mocks.listNotifications.mockResolvedValue(pageOf([grouped, older]));
  mocks.markNotificationRead.mockResolvedValue(undefined);
  mocks.markAllNotificationsRead.mockResolvedValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("NotificationCenter", () => {
  it("shows the unread count on the bell", async () => {
    mocks.getUnreadNotificationCount.mockResolvedValue(3);
    renderCenter();

    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
  });

  it("re-checks the unread count while the page is open", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderCenter();
    await waitFor(() => expect(mocks.getUnreadNotificationCount).toHaveBeenCalledTimes(1));

    mocks.getUnreadNotificationCount.mockResolvedValue(4);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NOTIFICATION_POLL_MS);
    });

    expect(mocks.getUnreadNotificationCount).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole("button", { name: "Notifications, 4 unread" })).toBeInTheDocument();
  });

  it("lists the title, message, time and read state", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);

    expect(mocks.listNotifications).toHaveBeenCalledWith(0);
    const unread = await panel.findByRole("button", { name: /^Unread: New upgrade recommendations!/ });
    expect(unread).toHaveTextContent("We found 3 new upgrade options for your iPhone 13.");
    expect(unread).toHaveTextContent("Just now · Unread");

    const read = panel.getByRole("button", { name: /^New upgrade recommendation!/ });
    expect(read).toHaveTextContent("· Read");
  });

  it("opens the recommendation card and marks the notification read", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.click(await panel.findByRole("button", { name: /^Unread: New upgrade recommendations!/ }));

    expect(mocks.markNotificationRead).toHaveBeenCalledWith(1);
    expect(screen.getByTestId("location")).toHaveTextContent("/dashboard?recommendation=42");
    expect(screen.queryByRole("dialog", { name: "Notifications" })).not.toBeInTheDocument();
    expect(bell()).toHaveAccessibleName("Notifications");
  });

  it("still opens the recommendation when marking it read fails", async () => {
    mocks.markNotificationRead.mockRejectedValue(new ApiError(500, "Something went wrong"));
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.click(await panel.findByRole("button", { name: /^Unread: New upgrade/ }));

    expect(screen.getByTestId("location")).toHaveTextContent("/dashboard?recommendation=42");
  });

  it("does not navigate for a recommendation that is no longer available", async () => {
    mocks.listNotifications.mockResolvedValue(
      pageOf([{ ...grouped, available: false }, { ...older, recommendationId: null, available: false }])
    );
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.click(await panel.findByRole("button", { name: /^Unread: New upgrade/ }));

    expect(mocks.markNotificationRead).toHaveBeenCalledWith(1);
    expect(screen.getByTestId("location")).toHaveTextContent("/devices");
    expect(panel.getAllByText("This recommendation is no longer available.")).toHaveLength(2);
  });

  it("marks all notifications as read", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);
    await panel.findByRole("button", { name: /^Unread: New upgrade/ });

    await user.click(panel.getByRole("button", { name: "Mark all as read" }));

    expect(mocks.markAllNotificationsRead).toHaveBeenCalled();
    expect(panel.queryByRole("button", { name: /^Unread:/ })).not.toBeInTheDocument();
    expect(panel.getByRole("button", { name: "Mark all as read" })).toBeDisabled();
  });

  it("loads older notifications", async () => {
    mocks.listNotifications
      .mockResolvedValueOnce(pageOf([grouped], 0, 2))
      .mockResolvedValueOnce(pageOf([older], 1, 2));
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.click(await panel.findByRole("button", { name: "View older notifications" }));

    expect(mocks.listNotifications).toHaveBeenLastCalledWith(1);
    expect(await panel.findByRole("button", { name: /^New upgrade recommendation!/ })).toBeInTheDocument();
    expect(panel.getByRole("button", { name: /^Unread: New upgrade recommendations!/ })).toBeInTheDocument();
    expect(panel.queryByRole("button", { name: "View older notifications" })).not.toBeInTheDocument();
  });

  it("shows an empty state when there are no notifications", async () => {
    mocks.listNotifications.mockResolvedValue(pageOf([]));
    const user = renderCenter();
    const panel = await openPanel(user);

    expect(await panel.findByRole("heading", { name: "No notifications yet" })).toBeInTheDocument();
  });

  it("shows an error when the list fails to load", async () => {
    mocks.listNotifications.mockRejectedValue(new ApiError(500, "Something went wrong"));
    const user = renderCenter();
    const panel = await openPanel(user);

    expect(await panel.findByRole("alert")).toHaveTextContent("Something went wrong");
  });

  it("says notifications are unavailable when the server has no notification routes", async () => {
    mocks.getUnreadNotificationCount.mockRejectedValue(new ApiError(401, "Unauthorized"));
    mocks.listNotifications.mockRejectedValue(new ApiError(401, "Unauthorized"));
    const user = renderCenter();

    await waitFor(() => expect(mocks.getUnreadNotificationCount).toHaveBeenCalled());
    expect(bell()).toHaveAccessibleName("Notifications");
    const panel = await openPanel(user);

    expect(await panel.findByRole("heading", { name: "Notifications aren’t available yet" })).toBeInTheDocument();
  });

  it("closes with Escape and returns focus to the bell", async () => {
    const user = renderCenter();
    await openPanel(user);

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Notifications" })).not.toBeInTheDocument();
    expect(bell()).toHaveFocus();
    expect(bell()).toHaveAttribute("aria-expanded", "false");
  });
});

describe("relativeTime", () => {
  const now = Date.parse("2026-10-19T12:00:00Z");

  it.each([
    ["2026-10-19T11:59:30Z", "Just now"],
    ["2026-10-19T11:55:00Z", "5m ago"],
    ["2026-10-19T09:00:00Z", "3h ago"],
    ["2026-10-17T12:00:00Z", "2d ago"],
  ])("%s -> %s", (createdAt, expected) => {
    expect(relativeTime(createdAt, now)).toBe(expected);
  });

  it("falls back to a date after a week", () => {
    expect(relativeTime("2026-10-01T12:00:00Z", now)).not.toMatch(/ago|Just now/);
  });
});
