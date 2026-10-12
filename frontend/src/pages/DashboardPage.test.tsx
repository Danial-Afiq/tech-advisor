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
  signOut: vi.fn(),
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/dashboard", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/dashboard")>()),
  listDashboardRecommendations:
    mocks.listDashboardRecommendations,
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
});
