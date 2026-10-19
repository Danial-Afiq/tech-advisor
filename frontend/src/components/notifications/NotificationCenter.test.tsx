import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client";
import { NotificationCenter } from "./NotificationCenter";
import type { AppNotification, NotificationPage } from "./types";

const mocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  getUnreadNotificationCount: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  dismissNotification: vi.fn(),
}));

vi.mock("../../api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api/notifications")>()),
  ...mocks,
}));

const strong: AppNotification = {
  notificationId: 1,
  recommendationId: 42,
  currentDeviceName: "My current phone",
  candidateBrand: "Samsung",
  candidateModelName: "Galaxy S26",
  verdict: "STRONG_UPGRADE_CANDIDATE",
  evidenceGrade: "A",
  tier: "HIGH",
  read: false,
  actionable: true,
  createdAt: "2026-10-18T09:00:00Z",
};

const watching: AppNotification = {
  ...strong,
  notificationId: 2,
  recommendationId: 43,
  candidateBrand: "Google",
  candidateModelName: "Pixel 11",
  verdict: "WORTH_WATCHING",
  evidenceGrade: "-",
  tier: "LOW",
  read: true,
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
  await user.click(screen.getByRole("button", { name: /^Notifications/ }));
  return within(screen.getByRole("dialog", { name: "Notifications" }));
}

const bell = () => screen.getByRole("button", { name: /^Notifications/ });
const item = (name: RegExp) => screen.getByRole("listitem", { name });

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getUnreadNotificationCount.mockResolvedValue(1);
  mocks.listNotifications.mockResolvedValue(pageOf([strong, watching]));
  mocks.markNotificationRead.mockResolvedValue(undefined);
  mocks.markAllNotificationsRead.mockResolvedValue(undefined);
  mocks.dismissNotification.mockResolvedValue(undefined);
});

describe("NotificationCenter", () => {
  it("shows the unread count on the bell", async () => {
    mocks.getUnreadNotificationCount.mockResolvedValue(3);
    renderCenter();

    expect(await screen.findByRole("button", { name: "Notifications, 3 unread" })).toBeInTheDocument();
  });

  it("lists notifications with product, verdict, evidence grade and tier", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);

    expect(mocks.listNotifications).toHaveBeenCalledWith({ read: "all", tier: null, page: 0, size: 10 });
    const unread = await panel.findByRole("listitem", { name: "Unread: Samsung Galaxy S26 for My current phone" });
    expect(within(unread).getByText("Strong upgrade candidate")).toBeInTheDocument();
    expect(within(unread).getByText("High confidence")).toBeInTheDocument();
    expect(within(unread).getByText("Owner evidence: A")).toBeInTheDocument();

    const read = item(/^Google Pixel 11/);
    expect(within(read).getByText("Low confidence")).toBeInTheDocument();
    expect(within(read).getByText("Not enough evidence yet")).toBeInTheDocument();
    expect(within(read).queryByRole("button", { name: "Mark as read" })).not.toBeInTheDocument();
  });

  it("filters by read state and tier on the server", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.selectOptions(panel.getByRole("combobox", { name: "Show" }), "unread");
    expect(mocks.listNotifications).toHaveBeenLastCalledWith({ read: "unread", tier: null, page: 0, size: 10 });

    await user.selectOptions(panel.getByRole("combobox", { name: "Tier" }), "HIGH");
    expect(mocks.listNotifications).toHaveBeenLastCalledWith({ read: "unread", tier: "HIGH", page: 0, size: 10 });
  });

  it("marks one notification as read and lowers the unread count", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);
    const unread = await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    await user.click(within(unread).getByRole("button", { name: "Mark as read" }));

    expect(mocks.markNotificationRead).toHaveBeenCalledWith(1);
    expect(await panel.findByRole("listitem", { name: /^Samsung Galaxy S26/ })).toBeInTheDocument();
    expect(bell()).toHaveAccessibleName("Notifications");
  });

  it("marks all notifications as read", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);
    await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    await user.click(panel.getByRole("button", { name: "Mark all as read" }));

    expect(mocks.markAllNotificationsRead).toHaveBeenCalled();
    expect(await panel.findByRole("listitem", { name: /^Samsung Galaxy S26/ })).toBeInTheDocument();
    expect(panel.getByRole("button", { name: "Mark all as read" })).toBeDisabled();
  });

  it("dismisses a notification without touching the recommendation", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);
    await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    await user.click(panel.getByRole("button", { name: "Dismiss Samsung Galaxy S26 for My current phone" }));

    expect(mocks.dismissNotification).toHaveBeenCalledWith(1);
    await waitFor(() => {
      expect(panel.queryByRole("listitem", { name: /Samsung Galaxy S26/ })).not.toBeInTheDocument();
    });
    expect(bell()).toHaveAccessibleName("Notifications");
  });

  it("opens the recommendation on the dashboard and marks it read", async () => {
    const user = renderCenter();
    const panel = await openPanel(user);
    const unread = await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    await user.click(within(unread).getByRole("button", { name: /View recommendation/ }));

    expect(mocks.markNotificationRead).toHaveBeenCalledWith(1);
    expect(screen.getByTestId("location")).toHaveTextContent("/dashboard?recommendation=42");
    expect(screen.queryByRole("dialog", { name: "Notifications" })).not.toBeInTheDocument();
  });

  it("does not offer to open a superseded or removed recommendation", async () => {
    mocks.listNotifications.mockResolvedValue(
      pageOf([{ ...strong, actionable: false }, { ...watching, recommendationId: null, actionable: false }])
    );
    const user = renderCenter();
    const panel = await openPanel(user);
    await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    expect(panel.queryByRole("button", { name: /View recommendation/ })).not.toBeInTheDocument();
    expect(panel.getAllByText("This recommendation has since been updated or removed.")).toHaveLength(2);
  });

  it("loads further pages", async () => {
    mocks.listNotifications
      .mockResolvedValueOnce(pageOf([strong], 0, 2))
      .mockResolvedValueOnce(pageOf([watching], 1, 2));
    const user = renderCenter();
    const panel = await openPanel(user);

    await user.click(await panel.findByRole("button", { name: "Load more" }));

    expect(mocks.listNotifications).toHaveBeenLastCalledWith({ read: "all", tier: null, page: 1, size: 10 });
    expect(await panel.findByRole("listitem", { name: /^Google Pixel 11/ })).toBeInTheDocument();
    expect(panel.getByRole("listitem", { name: /Samsung Galaxy S26/ })).toBeInTheDocument();
    expect(panel.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("shows an empty state when there are no notifications", async () => {
    mocks.listNotifications.mockResolvedValue(pageOf([]));
    const user = renderCenter();
    const panel = await openPanel(user);

    expect(await panel.findByRole("heading", { name: "No notifications yet" })).toBeInTheDocument();
  });

  it("shows an error and keeps the item when an action fails", async () => {
    mocks.dismissNotification.mockRejectedValue(new ApiError(500, "Something went wrong"));
    const user = renderCenter();
    const panel = await openPanel(user);
    await panel.findByRole("listitem", { name: /^Unread: Samsung/ });

    await user.click(panel.getByRole("button", { name: /^Dismiss Samsung/ }));

    expect(await panel.findByRole("alert")).toHaveTextContent("Something went wrong");
    expect(panel.getByRole("listitem", { name: /^Unread: Samsung/ })).toBeInTheDocument();
  });

  it("says notifications are unavailable when the server has no notification routes", async () => {
    mocks.getUnreadNotificationCount.mockRejectedValue(new ApiError(404, "Not Found"));
    mocks.listNotifications.mockRejectedValue(new ApiError(404, "Not Found"));
    const user = renderCenter();

    await waitFor(() => expect(mocks.getUnreadNotificationCount).toHaveBeenCalled());
    expect(bell()).toHaveAccessibleName("Notifications");
    const panel = await openPanel(user);

    expect(await panel.findByRole("heading", { name: "Notifications aren’t available yet" })).toBeInTheDocument();
    expect(panel.queryByRole("button", { name: "Mark all as read" })).not.toBeInTheDocument();
  });

  it("closes with Escape and returns focus to the bell", async () => {
    const user = renderCenter();
    await openPanel(user);
    expect(bell()).toHaveAttribute("aria-expanded", "true");

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Notifications" })).not.toBeInTheDocument();
    expect(bell()).toHaveFocus();
    expect(bell()).toHaveAttribute("aria-expanded", "false");
  });
});
