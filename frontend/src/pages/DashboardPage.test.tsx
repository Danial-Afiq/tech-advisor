import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DashboardPage from "./DashboardPage";
import { ApiError } from "../api/client";
import type { DashboardRecommendation } from "../api/dashboard";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  listDashboardRecommendations: vi.fn(),
  setRecommendationStarred: vi.fn(),
  signOut: vi.fn(),
  listDevices: vi.fn(),
  listSmartphones: vi.fn(),
}));

vi.mock("../api/devices", () => ({
  listDevices: mocks.listDevices,
}));

vi.mock("../api/catalogue", () => ({
  listSmartphones: mocks.listSmartphones,
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/dashboard", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/dashboard")>()),
  listDashboardRecommendations:
    mocks.listDashboardRecommendations,
  setRecommendationStarred: mocks.setRecommendationStarred,
}));

vi.mock("../api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/notifications")>()),
  getUnreadNotificationCount: vi.fn().mockResolvedValue(0),
  listNotifications: vi.fn().mockResolvedValue({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
}));

vi.mock("../api/auth", () => ({
  signOut: mocks.signOut,
}));

function CurrentSearch() {
  return <span data-testid="search">{useLocation().search}</span>;
}

function renderPage(entry = "/dashboard") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <CurrentSearch />
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

const recommended: DashboardRecommendation = {
  recommendationId: 1,
  currentDeviceId: 10,
  currentDeviceName: "My current phone",
  candidateProductId: 20,
  candidateBrand: "Samsung",
  candidateModelName: "Galaxy S26",
  latestPrice: 1299,
  currency: "SGD",
  verdict: "STRONG_UPGRADE_CANDIDATE",
  confidence: "A",
  reasoning: "This device provides a meaningful upgrade.",
  starred: false,
  createdAt: "2026-10-07T00:00:00Z",
};

const notRecommended: DashboardRecommendation = {
  recommendationId: 2,
  currentDeviceId: 10,
  currentDeviceName: "My current phone",
  candidateProductId: 21,
  candidateBrand: "Apple",
  candidateModelName: "iPhone 18",
  latestPrice: 1499,
  currency: "SGD",
  verdict: "NO_MEANINGFUL_CHANGE",
  confidence: "B",
  reasoning: "The improvement is not large enough.",
  starred: false,
  createdAt: "2026-10-07T00:00:00Z",
};

const worthConsidering: DashboardRecommendation = {
  recommendationId: 3,
  currentDeviceId: 11,
  currentDeviceName: "Second phone",
  candidateProductId: 22,
  candidateBrand: "Google",
  candidateModelName: "Pixel 11",
  latestPrice: 1199,
  currency: "SGD",
  verdict: "WORTH_CONSIDERING",
  confidence: "B",
  reasoning: "This upgrade is worth considering.",
  starred: false,
  createdAt: "2026-10-07T00:00:00Z",
};

const worthWatching: DashboardRecommendation = {
  recommendationId: 4,
  currentDeviceId: 12,
  currentDeviceName: "Third phone",
  candidateProductId: 23,
  candidateBrand: "OnePlus",
  candidateModelName: "OnePlus 15",
  latestPrice: 999,
  currency: "SGD",
  verdict: "WORTH_WATCHING",
  confidence: "C",
  reasoning: "This candidate is worth monitoring.",
  starred: false,
  createdAt: "2026-10-07T00:00:00Z",
};

const allRecommendations: DashboardRecommendation[] = [recommended, notRecommended, worthConsidering, worthWatching];

async function renderWithFilters(rows = allRecommendations) {
  mocks.listDashboardRecommendations.mockResolvedValue(rows);
  const user = userEvent.setup();
  renderPage();
  await user.click(await screen.findByRole("button", { name: "Filters" }));
  const dropdown = screen.getByRole("dialog", { name: "Filter recommendations" });
  return { user, controls: within(dropdown) };
}

function visibleCandidates() {
  const region = recommendationList();
  return region ? within(region) : null;
}

function recommendationList() {
  return screen.queryByRole("region", { name: "Device recommendations" });
}

function expectCandidateOrder(...names: string[]) {
  const text = recommendationList()?.textContent ?? "";
  const positions = names.map((name) => text.indexOf(name));

  positions.forEach((position) => expect(position).toBeGreaterThanOrEqual(0));
  positions.slice(1).forEach((position, index) => {
    expect(position).toBeGreaterThan(positions[index]);
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();

  mocks.getSession.mockReturnValue({
    token: "user-token",
    email: "user@example.com",
    role: "USER",
  });

  mocks.listDashboardRecommendations.mockResolvedValue([]);
  mocks.setRecommendationStarred.mockResolvedValue(undefined);
  mocks.listDevices.mockResolvedValue([]);
  mocks.listSmartphones.mockResolvedValue([]);
});

describe("DashboardPage", () => {
  it("filters by device ID and lists each device once", async () => {
    const { user, controls } = await renderWithFilters();
    const device = controls.getByRole("combobox", { name: "Owned device" });
    expect(within(device).getAllByRole("option")).toHaveLength(4);
    expect(device).toHaveFocus();

    await user.selectOptions(device, "10");

    expect(screen.getByRole("status")).toHaveTextContent("Showing 2 of 4 recommendations");
    expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
    expect(visibleCandidates()?.getByText("Apple iPhone 18")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Google Pixel 11")).not.toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("OnePlus OnePlus 15")).not.toBeInTheDocument();

    await user.selectOptions(device, "");
    expect(screen.getByRole("status")).toHaveTextContent("Showing 4 of 4 recommendations");
  });

  it("keeps owned devices with identical names separate", async () => {
    const { user, controls } = await renderWithFilters([
      recommended,
      { ...worthConsidering, currentDeviceName: recommended.currentDeviceName },
    ]);
    const device = controls.getByRole("combobox", { name: "Owned device" });
    expect(within(device).getByRole("option", { name: "My current phone (device 10)" })).toBeInTheDocument();
    expect(within(device).getByRole("option", { name: "My current phone (device 11)" })).toBeInTheDocument();
    await user.selectOptions(device, "11");
    expect(visibleCandidates()?.getByText("Google Pixel 11")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Samsung Galaxy S26")).not.toBeInTheDocument();
  });

  it("matches any selected verdict and restores everything when deselected", async () => {
    const { user, controls } = await renderWithFilters();
    const strong = controls.getByRole("checkbox", { name: "Strong upgrade candidate" });
    const watch = controls.getByRole("checkbox", { name: "Worth watching" });
    await user.click(strong);
    await user.click(watch);

    expect(screen.getByRole("status")).toHaveTextContent("Showing 2 of 4 recommendations");
    expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
    expect(visibleCandidates()?.getByText("OnePlus OnePlus 15")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Google Pixel 11")).not.toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Apple iPhone 18")).not.toBeInTheDocument();

    await user.click(strong);
    await user.click(watch);
    expect(screen.getByRole("status")).toHaveTextContent("Showing 4 of 4 recommendations");
  });

  it("matches any selected evidence grade", async () => {
    const { user, controls } = await renderWithFilters();
    await user.click(controls.getByRole("checkbox", { name: "B" }));
    await user.click(controls.getByRole("checkbox", { name: "C" }));

    expect(screen.getByRole("status")).toHaveTextContent("Showing 3 of 4 recommendations");
    expect(visibleCandidates()?.getByText("Google Pixel 11")).toBeInTheDocument();
    expect(visibleCandidates()?.getByText("Apple iPhone 18")).toBeInTheDocument();
    expect(visibleCandidates()?.getByText("OnePlus OnePlus 15")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Samsung Galaxy S26")).not.toBeInTheDocument();
  });

  it.each(["A", "B", "C", "D", "E", "F"] as const)("filters evidence grade %s", async (grade) => {
    const { user, controls } = await renderWithFilters([
      { ...recommended, confidence: grade },
      { ...worthConsidering, confidence: "-" },
    ]);
    await user.click(controls.getByRole("checkbox", { name: grade }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1 of 2 recommendations");
    expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Google Pixel 11")).not.toBeInTheDocument();
  });

  it("combines device, verdicts and grades with AND and clears all filters", async () => {
    const { user, controls } = await renderWithFilters();
    await user.selectOptions(controls.getByRole("combobox", { name: "Owned device" }), "10");
    await user.click(controls.getByRole("checkbox", { name: "Strong upgrade candidate" }));
    await user.click(controls.getByRole("checkbox", { name: "Worth considering" }));
    await user.click(controls.getByRole("checkbox", { name: "A" }));
    await user.click(controls.getByRole("checkbox", { name: "B" }));

    expect(screen.getByRole("status")).toHaveTextContent("Showing 1 of 4 recommendations");
    expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Google Pixel 11")).not.toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Apple iPhone 18")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Filters (5)" })).toHaveAttribute("aria-expanded", "true");
    // The overview and notification count continue to describe all active recommendations.
    expect(within(screen.getByRole("region", { name: "Recommendation summary" })).getByText("4")).toBeInTheDocument();

    await user.click(controls.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 4 of 4 recommendations");
    expect(controls.getByRole("combobox", { name: "Owned device" })).toHaveValue("");
    controls.getAllByRole("checkbox").forEach((checkbox) => expect(checkbox).not.toBeChecked());
    expect(mocks.listDashboardRecommendations).toHaveBeenCalledOnce();
  });

  it("shows an empty state for no matches and restores results from it", async () => {
    const { user, controls } = await renderWithFilters();
    await user.selectOptions(controls.getByRole("combobox", { name: "Owned device" }), "10");
    await user.click(controls.getByRole("checkbox", { name: "C" }));
    await user.click(controls.getByRole("button", { name: "Done" }));

    expect(screen.getByRole("heading", { name: "No recommendations match your filters" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Showing 0 of 4 recommendations");
    expect(visibleCandidates()).toBeNull();
    expect(screen.queryByText("We couldn’t load your dashboard.")).not.toBeInTheDocument();
    expect(screen.queryByText("No active recommendations yet")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 4 of 4 recommendations");
    expect(screen.queryByText("No recommendations match your filters")).not.toBeInTheDocument();
  });

  it("offers only grades A–F and keeps ungraded recommendations when no grade is selected", async () => {
    const { user, controls } = await renderWithFilters([
      recommended,
      { ...worthConsidering, confidence: null },
      { ...notRecommended, confidence: "-" },
    ]);
    const grades = within(controls.getByRole("group", { name: "Evidence grade" }));
    expect(grades.getAllByRole("checkbox")).toHaveLength(6);
    expect(controls.queryByRole("checkbox", { name: "Insufficient evidence (−)" })).not.toBeInTheDocument();
    expect(controls.queryByRole("checkbox", { name: "Not available" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Showing 3 of 3 recommendations");

    await user.click(grades.getByRole("checkbox", { name: "A" }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1 of 3 recommendations");
    expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Google Pixel 11")).not.toBeInTheDocument();
    expect(visibleCandidates()?.queryByText("Apple iPhone 18")).not.toBeInTheDocument();
    await user.click(grades.getByRole("checkbox", { name: "A" }));
    expect(screen.getByRole("status")).toHaveTextContent("Showing 3 of 3 recommendations");
    expect(visibleCandidates()?.getByText("Google Pixel 11")).toBeInTheDocument();
    expect(visibleCandidates()?.getByText("Apple iPhone 18")).toBeInTheDocument();
  });

  it("supports keyboard opening, Escape, outside clicks and preserved selections", async () => {
    mocks.listDashboardRecommendations.mockResolvedValue(allRecommendations);
    const user = userEvent.setup();
    renderPage();
    const trigger = await screen.findByRole("button", { name: "Filters" });
    trigger.focus();
    await user.keyboard("{Enter}");
    await user.selectOptions(screen.getByRole("combobox", { name: "Owned device" }), "11");
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Showing 1 of 4 recommendations");

    await user.click(trigger);
    expect(screen.getByRole("combobox", { name: "Owned device" })).toHaveValue("11");
    await user.click(screen.getByRole("heading", { name: "Your recommendations" }));
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

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

  it("does not show a separate Recommendations sidebar item", async () => {
    renderPage();

    await screen.findByRole("heading", {
      name: "No active recommendations yet",
    });

    expect(
      screen.queryByRole("button", { name: "Recommendations" })
    ).not.toBeInTheDocument();
  });

  it("only offers sidebar items that lead somewhere, each with a visible label", async () => {
    renderPage();

    await screen.findByRole("heading", {
      name: "No active recommendations yet",
    });

    const nav = screen.getByRole("list");
    expect(within(nav).getAllByRole("button").map((button) => button.textContent)).toEqual([
      "⌂Dashboard",
      "▣My devices",
    ]);
    expect(screen.queryByRole("button", { name: /Activity|Settings/ })).not.toBeInTheDocument();
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
      worthConsidering,
      worthWatching,
      notRecommended,
    ]);

    renderPage();

    expect(
        await screen.findByRole("heading", { name: "Samsung Galaxy S26" })
    ).toBeInTheDocument();

    expect(
        screen.getByRole("heading", { name: "Apple iPhone 18" })
    ).toBeInTheDocument();

    expect(
      screen.getByText("Strong upgrade candidate")
    ).toBeInTheDocument();

    expect(
      screen.getByText("Worth considering")
    ).toBeInTheDocument();

    expect(
      screen.getByText("Worth watching")
    ).toBeInTheDocument();

    expect(
      screen.getByText("No meaningful change")
    ).toBeInTheDocument();

    expect(
        screen.getByText(/1,299\.00/)
    ).toBeInTheDocument();

    const priceDisclaimer =
      "Prices are indicative and may vary by storage configuration, retailer, region and availability.";

    expect(screen.getAllByText(priceDisclaimer)).toHaveLength(1);
    expect(
      within(
        screen.getByRole("region", {
          name: "Recommendation controls",
        })
      ).getByText(priceDisclaimer)
    ).toBeInTheDocument();

    const summary = screen.getByRole("region", {
      name: "Recommendation summary",
    });

    expect(
      within(summary).getByText(
        "Active recommendations"
      )
    ).toBeInTheDocument();

    /*
     * There are four active recommendations across three devices.
     * The strong-upgrade and worth-considering devices require
     * attention. The worth-watching device does not.
     */
    expect(within(summary).getByText("4")).toBeInTheDocument();
    expect(within(summary).getByText("2")).toBeInTheDocument();
    expect(within(summary).getByText("1")).toBeInTheDocument();
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

  describe("sorting", () => {
    beforeEach(() => {
      mocks.listDashboardRecommendations.mockResolvedValue([
        recommended,
        notRecommended,
      ]);
    });

    it("defaults to most relevant and requests the default sort", async () => {
      renderPage();

      const select = await screen.findByRole("combobox", { name: "Sort by" });
      expect(select).toHaveValue("relevance");
      expect(select).toHaveDisplayValue("Most relevant");

      expect(
        mocks.listDashboardRecommendations
      ).toHaveBeenCalledWith("relevance");
    });

    it("shows the sort from the URL and requests it", async () => {
      renderPage("/dashboard?sort=price_desc");

      const select = await screen.findByRole("combobox", { name: "Sort by" });
      expect(select).toHaveValue("price_desc");
      expect(select).toHaveDisplayValue("Price: high to low");

      expect(
        mocks.listDashboardRecommendations
      ).toHaveBeenCalledWith("price_desc");
    });

    it("keeps filters and resets the page when the sort changes", async () => {
      const user = userEvent.setup();

      renderPage("/dashboard?verdict=WORTH_CONSIDERING&page=3");

      await user.selectOptions(
        await screen.findByRole("combobox", { name: "Sort by" }),
        "price_asc"
      );

      const search = new URLSearchParams(
        screen.getByTestId("search").textContent ?? ""
      );

      expect(search.get("sort")).toBe("price_asc");
      expect(search.get("verdict")).toBe("WORTH_CONSIDERING");
      expect(search.has("page")).toBe(false);

      expect(
        mocks.listDashboardRecommendations
      ).toHaveBeenLastCalledWith("price_asc");
    });

    it("drops the sort parameter when returning to the default", async () => {
      const user = userEvent.setup();

      renderPage("/dashboard?sort=recency&verdict=WORTH_WATCHING");

      await user.selectOptions(
        await screen.findByRole("combobox", { name: "Sort by" }),
        "relevance"
      );

      const search = new URLSearchParams(
        screen.getByTestId("search").textContent ?? ""
      );

      expect(search.has("sort")).toBe(false);
      expect(search.get("verdict")).toBe("WORTH_WATCHING");
    });

    it("falls back to the default for an unknown sort in the URL", async () => {
      renderPage("/dashboard?sort=banana");

      const select = await screen.findByRole("combobox", { name: "Sort by" });
      expect(select).toHaveValue("relevance");
      expect(select).toHaveDisplayValue("Most relevant");

      expect(
        mocks.listDashboardRecommendations
      ).toHaveBeenCalledWith("relevance");
    });

    it("keeps active filters while server results are re-sorted", async () => {
      const relevance = [
        recommended,
        worthConsidering,
        worthWatching,
        notRecommended,
      ];
      const priceDescending = [
        notRecommended,
        recommended,
        worthConsidering,
        worthWatching,
      ];
      mocks.listDashboardRecommendations.mockImplementation((selectedSort) =>
        Promise.resolve(
          selectedSort === "price_desc" ? priceDescending : relevance
        )
      );
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Filters" }));
      await user.click(screen.getByRole("checkbox", { name: "B" }));
      expectCandidateOrder("Google Pixel 11", "Apple iPhone 18");

      await user.selectOptions(
        screen.getByRole("combobox", { name: "Sort by" }),
        "price_desc"
      );

      await waitFor(() => {
        expect(mocks.listDashboardRecommendations).toHaveBeenLastCalledWith(
          "price_desc"
        );
        expectCandidateOrder("Apple iPhone 18", "Google Pixel 11");
      });
      expect(screen.getByRole("button", { name: "Filters (1)" })).toBeInTheDocument();
      expect(screen.getByRole("status")).toHaveTextContent(
        "Showing 2 of 4 recommendations"
      );
    });

    it("filters an already sorted result without changing its order", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue([
        worthWatching,
        worthConsidering,
        recommended,
        notRecommended,
      ]);
      const user = userEvent.setup();
      renderPage("/dashboard?sort=price_asc");

      await user.click(await screen.findByRole("button", { name: "Filters" }));
      await user.click(screen.getByRole("checkbox", { name: "B" }));

      expectCandidateOrder("Google Pixel 11", "Apple iPhone 18");
      expect(screen.getByRole("combobox", { name: "Sort by" })).toHaveValue(
        "price_asc"
      );
      expect(mocks.listDashboardRecommendations).toHaveBeenCalledOnce();
    });

    it("clears filters without resetting the selected sort", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue([
        notRecommended,
        recommended,
        worthConsidering,
        worthWatching,
      ]);
      const user = userEvent.setup();
      renderPage("/dashboard?sort=price_desc");

      await user.click(await screen.findByRole("button", { name: "Filters" }));
      const dropdown = screen.getByRole("dialog", {
        name: "Filter recommendations",
      });
      await user.click(within(dropdown).getByRole("checkbox", { name: "B" }));
      await user.click(within(dropdown).getByRole("button", { name: "Clear filters" }));

      expect(screen.getByRole("combobox", { name: "Sort by" })).toHaveValue(
        "price_desc"
      );
      expect(screen.getByTestId("search")).toHaveTextContent("sort=price_desc");
      expect(screen.getByRole("status")).toHaveTextContent(
        "Showing 4 of 4 recommendations"
      );
      expect(mocks.listDashboardRecommendations).toHaveBeenCalledOnce();
    });

    it("ignores an older response after the sort changes again", async () => {
      const priceAscending = deferred<DashboardRecommendation[]>();
      const priceDescending = deferred<DashboardRecommendation[]>();
      mocks.listDashboardRecommendations.mockImplementation((selectedSort) => {
        if (selectedSort === "price_asc") return priceAscending.promise;
        if (selectedSort === "price_desc") return priceDescending.promise;
        return Promise.resolve([recommended, notRecommended]);
      });
      const user = userEvent.setup();
      renderPage();
      const sortSelect = await screen.findByRole("combobox", { name: "Sort by" });

      await user.selectOptions(sortSelect, "price_asc");
      await waitFor(() => {
        expect(mocks.listDashboardRecommendations).toHaveBeenLastCalledWith(
          "price_asc"
        );
      });
      await user.selectOptions(sortSelect, "price_desc");
      await waitFor(() => {
        expect(mocks.listDashboardRecommendations).toHaveBeenLastCalledWith(
          "price_desc"
        );
      });

      await act(async () => {
        priceDescending.resolve([notRecommended, recommended]);
        await priceDescending.promise;
      });
      await waitFor(() => {
        expectCandidateOrder("Apple iPhone 18", "Samsung Galaxy S26");
      });

      await act(async () => {
        priceAscending.resolve([worthConsidering]);
        await priceAscending.promise;
      });
      expectCandidateOrder("Apple iPhone 18", "Samsung Galaxy S26");
      expect(screen.queryByText("Google Pixel 11")).not.toBeInTheDocument();
    });

    it("keeps result count, filters and sort in one responsive toolbar", async () => {
      renderPage();

      const controls = await screen.findByRole("region", {
        name: "Recommendation controls",
      });
      expect(within(controls).getByRole("status")).toBeInTheDocument();
      expect(within(controls).getByRole("button", { name: "Filters" })).toBeInTheDocument();
      expect(within(controls).getByRole("combobox", { name: "Sort by" })).toBeInTheDocument();
      expect(controls).toHaveClass("flex-wrap");
    });
  });

  describe("starred recommendations", () => {
    async function renderStarred(rows = allRecommendations) {
      mocks.listDashboardRecommendations.mockResolvedValue(rows);
      const user = userEvent.setup();
      renderPage();
      await screen.findByRole("region", { name: "Device recommendations" });
      return user;
    }

    const starFor = (name: RegExp) =>
      screen.getByRole("button", { name });

    it("stars a recommendation, saves it and shows the star as selected", async () => {
      const user = await renderStarred();
      const star = starFor(/^Save Samsung Galaxy S26 for My current phone$/);
      expect(star).toHaveAttribute("aria-pressed", "false");

      await user.click(star);

      expect(mocks.setRecommendationStarred).toHaveBeenCalledWith(1, true);
      const starred = await screen.findByRole("button", {
        name: /^Remove Samsung Galaxy S26 for My current phone$/,
      });
      expect(starred).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByText("Recommendation saved")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Saved (1)" })).toBeInTheDocument();
    });

    it("shows only saved recommendations when the Saved toggle is on", async () => {
      const user = await renderStarred([
        { ...recommended, starred: true },
        notRecommended,
        { ...worthConsidering, starred: true },
      ]);
      const saved = screen.getByRole("button", { name: "Saved (2)" });

      await user.click(saved);

      expect(saved).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByText("Showing 2 of 3 recommendations")).toBeInTheDocument();
      expect(visibleCandidates()?.getByText("Samsung Galaxy S26")).toBeInTheDocument();
      expect(visibleCandidates()?.getByText("Google Pixel 11")).toBeInTheDocument();
      expect(visibleCandidates()?.queryByText("Apple iPhone 18")).not.toBeInTheDocument();

      // Clearing the dropdown's filters leaves the Saved toggle alone.
      await user.click(screen.getByRole("button", { name: "Filters" }));
      await user.click(screen.getByRole("checkbox", { name: "A" }));
      await user.click(screen.getByRole("button", { name: "Clear filters" }));
      expect(saved).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByText("Showing 2 of 3 recommendations")).toBeInTheDocument();
    });

    it("asks for confirmation before removing, and keeps it saved on cancel", async () => {
      const user = await renderStarred([{ ...recommended, starred: true }]);

      await user.click(starFor(/^Remove Samsung Galaxy S26/));

      const dialog = screen.getByRole("dialog", { name: "Remove saved recommendation?" });
      expect(within(dialog).getByText(/Samsung Galaxy S26 for My current phone/)).toBeInTheDocument();
      expect(mocks.setRecommendationStarred).not.toHaveBeenCalled();

      await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

      expect(screen.queryByRole("dialog", { name: "Remove saved recommendation?" })).not.toBeInTheDocument();
      expect(mocks.setRecommendationStarred).not.toHaveBeenCalled();
      expect(starFor(/^Remove Samsung Galaxy S26/)).toHaveAttribute("aria-pressed", "true");
    });

    it("removes a confirmed recommendation from the saved view", async () => {
      const user = await renderStarred([
        { ...recommended, starred: true },
        { ...worthConsidering, starred: true },
      ]);
      await user.click(screen.getByRole("button", { name: "Saved (2)" }));

      await user.click(starFor(/^Remove Samsung Galaxy S26/));
      await user.click(
        within(screen.getByRole("dialog", { name: "Remove saved recommendation?" }))
          .getByRole("button", { name: "Remove" })
      );

      expect(mocks.setRecommendationStarred).toHaveBeenCalledWith(1, false);
      await waitFor(() => {
        expect(visibleCandidates()?.queryByText("Samsung Galaxy S26")).not.toBeInTheDocument();
      });
      expect(visibleCandidates()?.getByText("Google Pixel 11")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Saved (1)" })).toBeInTheDocument();
      expect(screen.getByText("Removed from saved")).toBeInTheDocument();
    });

    it("shows an empty state when nothing is saved", async () => {
      const user = await renderStarred();

      await user.click(screen.getByRole("button", { name: "Saved (0)" }));

      expect(screen.getByRole("heading", { name: "No saved recommendations yet" })).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Show all recommendations" }));
      expect(screen.getByText("Showing 4 of 4 recommendations")).toBeInTheDocument();
    });

    it("keeps the star unselected and explains when saving fails", async () => {
      mocks.setRecommendationStarred.mockRejectedValue(
        new ApiError(500, "Something went wrong")
      );
      const user = await renderStarred();

      await user.click(starFor(/^Save Samsung Galaxy S26/));

      expect(await screen.findByText("Couldn’t save recommendation")).toBeInTheDocument();
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
      expect(starFor(/^Save Samsung Galaxy S26/)).toHaveAttribute("aria-pressed", "false");
      expect(mocks.listDashboardRecommendations).toHaveBeenCalledTimes(1);
    });

    it("reloads the dashboard when the recommendation was replaced in the background", async () => {
      mocks.setRecommendationStarred.mockRejectedValue(
        new ApiError(404, "Recommendation not found")
      );
      const user = await renderStarred([recommended]);
      // The re-assessed row has a new id and kept the user's earlier star.
      mocks.listDashboardRecommendations.mockResolvedValue([
        { ...recommended, recommendationId: 99, starred: true },
      ]);

      await user.click(starFor(/^Save Samsung Galaxy S26/));

      expect(await screen.findByText("Recommendation was just updated")).toBeInTheDocument();
      expect(mocks.listDashboardRecommendations).toHaveBeenCalledTimes(2);
      expect(mocks.listDashboardRecommendations).toHaveBeenLastCalledWith("relevance");
      expect(
        await screen.findByRole("button", { name: /^Remove Samsung Galaxy S26/ })
      ).toHaveAttribute("aria-pressed", "true");
      expect(screen.queryByText("Couldn’t save recommendation")).not.toBeInTheDocument();
    });
  });

  describe("opened from a notification", () => {
    it("outlines the recommendation named in the URL", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(allRecommendations);
      renderPage("/dashboard?recommendation=3");

      const card = await screen.findByRole("article", { name: "Recommendation for Second phone" });
      expect(card).toHaveAttribute("aria-current", "true");
      expect(card).toHaveAttribute("id", "recommendation-3");
      expect(
        screen.getByRole("article", { name: "Recommendation for Third phone" })
      ).not.toHaveAttribute("aria-current");
    });

    it("explains when the opened recommendation is no longer active", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(allRecommendations);
      renderPage("/dashboard?recommendation=999");

      expect(
        await screen.findByText(/The recommendation you opened is no longer active/)
      ).toBeInTheDocument();
    });

    it("offers to clear filters that hide the opened recommendation", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(allRecommendations);
      const user = userEvent.setup();
      renderPage("/dashboard?recommendation=3");
      await screen.findByRole("article", { name: "Recommendation for Second phone" });

      await user.click(screen.getByRole("button", { name: /^Saved/ }));
      expect(screen.getByText(/hidden by your current filters/)).toBeInTheDocument();

      await user.click(screen.getAllByRole("button", { name: "Clear filters" })[0]);
      expect(
        screen.getByRole("article", { name: "Recommendation for Second phone" })
      ).toHaveAttribute("aria-current", "true");
    });

    it("uses the real notification bell instead of the attention count", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(allRecommendations);
      renderPage();
      await screen.findByRole("region", { name: "Device recommendations" });

      expect(screen.getByRole("button", { name: "Notifications" })).toHaveAttribute("aria-expanded", "false");
    });
  });

  describe("top recommendations per device", () => {
    const forDevice = (deviceId: number, deviceName: string, count: number, firstId: number) =>
      Array.from({ length: count }, (_, index): DashboardRecommendation => ({
        ...worthConsidering,
        recommendationId: firstId + index,
        currentDeviceId: deviceId,
        currentDeviceName: deviceName,
        candidateProductId: 100 + firstId + index,
        candidateModelName: `Pick ${firstId + index}`,
      }));

    it("shows the top picks for every device and expands one device on request", async () => {
      const user = userEvent.setup();
      mocks.listDashboardRecommendations.mockResolvedValue([
        ...forDevice(10, "My current phone", 5, 1),
        ...forDevice(11, "Second phone", 4, 20),
      ]);

      renderPage();

      const first = await screen.findByRole("region", { name: "Recommendations for My current phone" });
      const second = screen.getByRole("region", { name: "Recommendations for Second phone" });
      expect(within(first).getAllByRole("article")).toHaveLength(3);
      expect(within(second).getAllByRole("article")).toHaveLength(3);
      expect(within(first).getByText("Top 3 of 5 recommendations")).toBeInTheDocument();
      // The count still describes every matching recommendation.
      expect(screen.getByRole("status")).toHaveTextContent("Showing 9 of 9 recommendations");

      // Server order is kept, so the top three are the first three returned.
      expect(within(first).getByRole("heading", { name: "Google Pick 3" })).toBeInTheDocument();
      expect(within(first).queryByRole("heading", { name: "Google Pick 4" })).not.toBeInTheDocument();

      await user.click(within(first).getByRole("button", { name: "Show all 5" }));
      expect(within(first).getAllByRole("article")).toHaveLength(5);
      expect(within(second).getAllByRole("article")).toHaveLength(3);

      await user.click(within(first).getByRole("button", { name: "Show top 3" }));
      expect(within(first).getAllByRole("article")).toHaveLength(3);
    });

    it("does not offer Show all when a device has only a few recommendations", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(forDevice(10, "My current phone", 2, 1));

      renderPage();

      const group = await screen.findByRole("region", { name: "Recommendations for My current phone" });
      expect(within(group).getAllByRole("article")).toHaveLength(2);
      expect(within(group).queryByRole("button", { name: /Show all/ })).not.toBeInTheDocument();
    });

    it("expands a device when the recommendation opened from a notification is past its top picks", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue(forDevice(10, "My current phone", 5, 1));

      renderPage("/dashboard?recommendation=5");

      const group = await screen.findByRole("region", { name: "Recommendations for My current phone" });
      expect(within(group).getAllByRole("article")).toHaveLength(5);
      expect(within(group).getByRole("heading", { name: "Google Pick 5" })).toBeInTheDocument();
    });
  });

  describe("spec comparison", () => {
    const catalogueItem = (id: number, brand: string, modelName: string, batteryMah: number) => ({
      id, brand, modelName, releaseDate: null, status: "VERIFIED",
      chipset: "Chip", ramGb: 8, cpuGhz: null, storageGb: 256, batteryMah,
      wiredChargingWatts: null, wirelessChargingWatts: null, displaySizeInches: null,
      refreshRateHz: null, weightG: null, cameraSpecs: null, pixelDensity: null,
      ipRating: null, os: null, softwareSupportYears: null, latestPrice: null, currency: null,
    });

    it("shows the owned device and the suggested product side by side", async () => {
      const user = userEvent.setup();
      mocks.listDashboardRecommendations.mockResolvedValue([{
        ...recommended,
        specChanges: [
          { spec: "battery_mah", deltaPct: 54.3 },
          { spec: "ram_gb", deltaPct: 33.3 },
          { spec: "benchmark_score", deltaPct: 20 },
          // Lighter is better: the backend reports +19.6 for a 240 g -> 193 g change.
          { spec: "weight_g", deltaPct: 19.6 },
          { spec: "cpu_ghz", deltaPct: 0.4 },
        ],
      }]);
      mocks.listDevices.mockResolvedValue([{
        id: 10, productId: 5, productBrand: "Apple", productModelName: "iPhone 13",
        productCategory: "SMARTPHONE", customName: null, purchaseDate: null, condition: null,
        satisfactionScore: null, useCases: "[]", specOverrides: '{"ram_gb":6}', budget: 1000,
        currency: "SGD", upgradeUrgency: null, brandFlexibility: null, priorities: null,
        painPoints: null, notes: null, current: true, createdAt: "", updatedAt: "",
      }]);
      mocks.listSmartphones.mockResolvedValue([
        { ...catalogueItem(5, "Apple", "iPhone 13", 3240), weightG: 240 },
        { ...catalogueItem(20, "Samsung", "Galaxy S26", 5000), weightG: 193, imageUrl: "https://img.example/s26.png" },
      ]);

      renderPage();

      const card = await screen.findByRole("article", { name: "Recommendation for My current phone" });
      // Top three, and sub-1% changes are noise.
      expect(await within(card).findByText("Battery 54% better · RAM 33% better · Benchmark 20% better"))
        .toBeInTheDocument();
      expect(within(card).getByRole("img", { name: "Samsung Galaxy S26 product image" }))
        .toHaveAttribute("src", "https://img.example/s26.png");
      // Battery, RAM (the user's override) and weight differ; chipset and storage match.
      await user.click(await within(card).findByRole("button", { name: /Compare specs · 3 differences/ }));
      const dialog = screen.getByRole("dialog", { name: "My current phone vs Samsung Galaxy S26" });
      const table = within(dialog).getByRole("table");
      expect(within(table).getByRole("columnheader", { name: "Your device" })).toBeInTheDocument();
      expect(within(table).getByRole("columnheader", { name: "Suggested" })).toBeInTheDocument();
      const row = (label: string) => within(table).getByRole("rowheader", { name: label }).closest("tr");
      const battery = row("Battery");
      expect(battery).toHaveTextContent("3,240 mAh");
      expect(battery).toHaveTextContent("5,000 mAh▲ better");
      expect(row("RAM")).toHaveTextContent("RAM6 GB8 GB▲ better");
      // The arrow follows the value (it went down); the colour says it is better.
      expect(row("Weight")).toHaveTextContent("Weight240 g193 g▼ better");
      expect(within(row("Weight")!).getByText("193 g", { exact: false })).toHaveClass("text-success");
      expect(row("Storage")).toHaveTextContent("256 GB256 GB");
    });

    it("says the comparison is unavailable when specs can't be loaded", async () => {
      mocks.listDashboardRecommendations.mockResolvedValue([recommended]);
      mocks.listDevices.mockRejectedValue(new Error("offline"));
      mocks.listSmartphones.mockRejectedValue(new Error("offline"));

      renderPage();

      const card = await screen.findByRole("article", { name: "Recommendation for My current phone" });
      expect(await within(card).findByText(/not available/)).toBeInTheDocument();
      // A failed comparison is not a page error.
      expect(screen.queryByText(/couldn’t load your dashboard/)).not.toBeInTheDocument();
    });
  });
});
