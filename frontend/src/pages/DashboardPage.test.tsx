import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DashboardPage from "./DashboardPage";
import { ApiError } from "../api/client";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  listDashboardRecommendations: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/dashboard", () => ({
  listDashboardRecommendations:
    mocks.listDashboardRecommendations,
}));

vi.mock("../api/auth", () => ({
  signOut: mocks.signOut,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/dashboard"]}>
      <Routes>
        <Route
          path="/dashboard"
          element={<DashboardPage />}
        />

        <Route
          path="/login"
          element={<p>login route</p>}
        />

        <Route
          path="/devices"
          element={<p>devices route</p>}
        />
      </Routes>
    </MemoryRouter>
  );
}

const recommended = {
  recommendationId: 1,
  currentDeviceId: 10,
  currentDeviceName: "My current phone",
  candidateProductId: 20,
  candidateBrand: "Samsung",
  candidateModelName: "Galaxy S26",
  latestPrice: 1299,
  currency: "SGD",
  verdict: "RECOMMENDED",
  confidence: "A",
  reasoning: "This device provides a meaningful upgrade.",
  createdAt: "2026-10-07T00:00:00Z",
};

const notRecommended = {
  recommendationId: 2,
  currentDeviceId: 10,
  currentDeviceName: "My current phone",
  candidateProductId: 21,
  candidateBrand: "Apple",
  candidateModelName: "iPhone 18",
  latestPrice: 1499,
  currency: "SGD",
  verdict: "NOT_RECOMMENDED",
  confidence: "B",
  reasoning: "The improvement is not large enough.",
  createdAt: "2026-10-07T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();

  mocks.getSession.mockReturnValue({
    token: "user-token",
    email: "user@example.com",
    role: "USER",
  });

  mocks.listDashboardRecommendations.mockResolvedValue([]);
});

describe("DashboardPage", () => {
  it("shows the empty state when there are no recommendations", async () => {
    renderPage();

    expect(
      screen.getByText("Loading your recommendations…")
    ).toBeInTheDocument();

    expect(
      await screen.findByRole("heading", {
        name: "No active recommendations yet",
      })
    ).toBeInTheDocument();

    expect(
      screen.getByRole("button", {
        name: "View my devices",
      })
    ).toBeInTheDocument();
 });

  it("navigates between the dashboard and devices pages", async () => {
    const user = userEvent.setup();

    renderPage();

    await user.click(
      screen.getByRole("button", {
        name: "Dashboard",
      })
    );

    await user.click(
      screen.getByRole("button", {
        name: "My devices",
      })
    );

    expect(
      await screen.findByText("devices route")
    ).toBeInTheDocument();
  });

  it("signs out from the sidebar and returns to login", async () => {
    const user = userEvent.setup();

    renderPage();

    await user.click(
      screen.getByRole("button", {
        name: "Sign out",
      })
    );

    expect(mocks.signOut).toHaveBeenCalledOnce();
    expect(await screen.findByText("login route")).toBeInTheDocument();
  });

  it("shows recommendation details and summary counts", async () => {
    mocks.listDashboardRecommendations.mockResolvedValue([
      recommended,
      notRecommended,
    ]);

    renderPage();

    expect(
        await screen.findByText((_, element) => {
            return (
            element?.tagName.toLowerCase() === "p" &&
            element.textContent?.replace(/\s+/g, " ").trim() ===
                "Samsung Galaxy S26"
            );
        })
    ).toBeInTheDocument();

    expect(
        screen.getByText((_, element) => {
            return (
            element?.tagName.toLowerCase() === "p" &&
            element.textContent?.replace(/\s+/g, " ").trim() ===
                "Apple iPhone 18"
            );
        })
    ).toBeInTheDocument();

    expect(
      screen.getByText("Upgrade recommended")
    ).toBeInTheDocument();

    expect(
      screen.getByText("No upgrade needed")
    ).toBeInTheDocument();

    expect(
        screen.getByText(/1,299\.00/)
    ).toBeInTheDocument();

    const summary = screen.getByRole("region", {
      name: "Recommendation summary",
    });

    expect(
      within(summary).getByText(
        "Active recommendations"
      )
    ).toBeInTheDocument();

    expect(within(summary).getByText("2")).toBeInTheDocument();

    /*
     * Both recommendation rows belong to the same device.
     * Therefore, the device must only be counted once.
     */
    expect(within(summary).getByText("1")).toBeInTheDocument();

    expect(within(summary).getByText("0")).toBeInTheDocument();
  });

  it("shows an error message when the dashboard request fails", async () => {
    mocks.listDashboardRecommendations.mockRejectedValue(
      new Error("Server unavailable")
    );

    renderPage();

    expect(
      await screen.findByText(
        "We couldn’t load your dashboard."
      )
    ).toBeInTheDocument();

    expect(
      screen.getByText("Server unavailable")
    ).toBeInTheDocument();

    expect(
      screen.getByRole("button", {
        name: "Try again",
      })
    ).toBeInTheDocument();
  });

  it("redirects to login when the session is unauthorized", async () => {
    mocks.listDashboardRecommendations.mockRejectedValue(
      new ApiError(401, "Session expired")
    );

    renderPage();

    expect(
      await screen.findByText("login route")
    ).toBeInTheDocument();
  });
});
