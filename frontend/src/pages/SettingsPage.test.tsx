import {
  render,
  screen,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  MemoryRouter,
  Route,
  Routes,
} from "react-router-dom";
import {
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { ApiError } from "../api/client";
import SettingsPage from "./SettingsPage";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  getProfile: vi.fn(),
  updateRecommendationEmailPreference: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/auth", () => ({
  getProfile: mocks.getProfile,
  updateRecommendationEmailPreference:
    mocks.updateRecommendationEmailPreference,
  signOut: mocks.signOut,
}));

vi.mock("../api/notifications", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("../api/notifications")
  >()),
  getUnreadNotificationCount: vi
    .fn()
    .mockResolvedValue(0),
  listNotifications: vi.fn().mockResolvedValue({
    items: [],
    page: 0,
    size: 10,
    totalItems: 0,
    totalPages: 0,
  }),
}));

const profile = {
  id: 1,
  email: "user@example.com",
  role: "USER",
  createdAt: "2026-10-25T00:00:00Z",
  recommendationEmailsEnabled: true,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/settings"]}>
      <Routes>
        <Route
          path="/settings"
          element={<SettingsPage />}
        />
        <Route
          path="/dashboard"
          element={<p>dashboard route</p>}
        />
        <Route
          path="/devices"
          element={<p>devices route</p>}
        />
        <Route
          path="/login"
          element={<p>login route</p>}
        />
      </Routes>
    </MemoryRouter>
  );
}

async function openNotificationSettings(
  user: ReturnType<typeof userEvent.setup>
) {
  const sections = await screen.findByRole(
    "navigation",
    {
      name: "Settings sections",
    }
  );

  await user.click(
    within(sections).getByRole("button", {
      name: /Notifications/,
    })
  );

  return screen.findByRole("checkbox", {
    name: "Recommendation email notifications",
  });
}

beforeEach(() => {
  vi.clearAllMocks();

  mocks.getSession.mockReturnValue({
    token: "user-token",
    email: "user@example.com",
    role: "USER",
  });

  mocks.getProfile.mockResolvedValue(profile);

  mocks.updateRecommendationEmailPreference.mockImplementation(
    (enabled: boolean) =>
      Promise.resolve({
        ...profile,
        recommendationEmailsEnabled: enabled,
      })
  );
});

describe("SettingsPage", () => {
  it("loads the account settings overview", async () => {
    renderPage();

    expect(
      screen.getByText("Loading your settings…")
    ).toBeInTheDocument();

    const sections = await screen.findByRole(
      "navigation",
      {
        name: "Settings sections",
      }
    );

    expect(mocks.getProfile).toHaveBeenCalledOnce();

    expect(
      within(sections).getByText("Profile")
    ).toBeInTheDocument();

    expect(
      within(sections).getByText("Notifications")
    ).toBeInTheDocument();

    expect(
      within(sections).getByText("Activity")
    ).toBeInTheDocument();

    expect(
      within(sections).getAllByText("Coming soon")
    ).toHaveLength(2);

    expect(
      screen.queryByRole("checkbox", {
        name: "Recommendation email notifications",
      })
    ).not.toBeInTheDocument();
  });

  it("opens notifications and returns to the overview", async () => {
    const user = userEvent.setup();
    renderPage();

    const toggle =
      await openNotificationSettings(user);

    expect(toggle).toBeChecked();

    await user.click(
      screen.getByRole("button", {
        name: "Back to account settings",
      })
    );

    expect(
      await screen.findByRole("navigation", {
        name: "Settings sections",
      })
    ).toBeInTheDocument();

    expect(
      screen.queryByRole("checkbox", {
        name: "Recommendation email notifications",
      })
    ).not.toBeInTheDocument();
  });

  it("allows the user to disable and enable recommendation emails", async () => {
    const user = userEvent.setup();
    renderPage();

    const toggle =
      await openNotificationSettings(user);

    await user.click(toggle);

    expect(
      mocks.updateRecommendationEmailPreference
    ).toHaveBeenCalledWith(false);

    expect(
      await screen.findByText(
        "Recommendation emails disabled."
      )
    ).toBeInTheDocument();

    expect(
      screen.getByRole("status")
    ).toHaveClass("text-[#ff7f8c]");

    expect(toggle).not.toBeChecked();

    await user.click(toggle);

    expect(
      mocks.updateRecommendationEmailPreference
    ).toHaveBeenLastCalledWith(true);

    expect(
      await screen.findByText(
        "Recommendation emails enabled."
      )
    ).toBeInTheDocument();

    expect(
      screen.getByRole("status")
    ).toHaveClass("text-[#83e4c1]");

    expect(toggle).toBeChecked();
  });

  it("shows an error without changing the saved preference", async () => {
    const user = userEvent.setup();

    mocks.updateRecommendationEmailPreference
      .mockRejectedValue(
        new Error("Server unavailable")
      );

    renderPage();

    const toggle =
      await openNotificationSettings(user);

    await user.click(toggle);

    expect(
      await screen.findByRole("alert")
    ).toHaveTextContent("Server unavailable");

    expect(toggle).toBeChecked();
  });

  it("redirects to login when the session is unauthorized", async () => {
    mocks.getProfile.mockRejectedValue(
      new ApiError(401, "Session expired")
    );

    renderPage();

    expect(
      await screen.findByText("login route")
    ).toBeInTheDocument();
  });

  it("navigates to the devices page from the sidebar", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("navigation", {
      name: "Settings sections",
    });

    await user.click(
      screen.getByRole("button", {
        name: "My devices",
      })
    );

    expect(
      await screen.findByText("devices route")
    ).toBeInTheDocument();
  });

  it("signs out and returns to login", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("navigation", {
      name: "Settings sections",
    });

    await user.click(
      screen.getByRole("button", {
        name: "Sign out",
      })
    );

    expect(mocks.signOut).toHaveBeenCalledOnce();

    expect(
      await screen.findByText("login route")
    ).toBeInTheDocument();
  });
});