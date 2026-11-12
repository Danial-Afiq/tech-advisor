import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { emptyRecommendationFilters, filterRecommendations } from "./recommendationOptions";
import { SpecificationFilterControl } from "./SpecificationFilterControl";
import type { DashboardRecommendation, RecommendationFilters } from "./types";

const rows: DashboardRecommendation[] = [
  {
    recommendationId: 1,
    currentDeviceId: 10,
    currentDeviceName: "My phone",
    candidateProductId: 20,
    candidateBrand: "Example",
    candidateModelName: "Twelve",
    candidateCategory: "SMARTPHONE",
    candidateSpecifications: { ram_gb: 12, os: "Android" },
    latestPrice: 1000,
    currency: "SGD",
    verdict: "WORTH_CONSIDERING",
    confidence: "B",
    reasoning: "Test",
    starred: false,
    createdAt: "2026-11-13T00:00:00Z",
  },
  {
    recommendationId: 2,
    currentDeviceId: 10,
    currentDeviceName: "My phone",
    candidateProductId: 21,
    candidateBrand: "Example",
    candidateModelName: "Eight",
    candidateCategory: "SMARTPHONE",
    candidateSpecifications: { ram_gb: 8, os: "Android" },
    latestPrice: 900,
    currency: "SGD",
    verdict: "WORTH_WATCHING",
    confidence: "C",
    reasoning: "Test",
    starred: false,
    createdAt: "2026-11-13T00:00:00Z",
  },
];

function Harness() {
  const [filters, setFilters] = useState<RecommendationFilters>(emptyRecommendationFilters);
  const visible = filterRecommendations(rows, filters);
  return (
    <>
      <SpecificationFilterControl recommendations={rows} filters={filters} onChange={setFilters} />
      <output>{visible.map((row) => row.candidateModelName).join(", ")}</output>
    </>
  );
}

describe("SpecificationFilterControl", () => {
  it("adds and applies an inclusive numeric range for the selected device", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole("button", { name: "Specifications" }));
    const modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.selectOptions(within(modal).getByLabelText("Add specification"), "ram_gb");
    await user.click(within(modal).getByRole("button", { name: "Add filter" }));
    await user.type(within(modal).getByLabelText("Minimum RAM"), "12");
    await user.type(within(modal).getByLabelText("Maximum RAM"), "16");
    await user.click(within(modal).getByRole("button", { name: "Apply filters" }));

    expect(screen.getByRole("button", { name: "Specifications (1)" })).toBeInTheDocument();
    expect(screen.getByText("Twelve")).toBeInTheDocument();
    expect(screen.queryByText("Eight")).not.toBeInTheDocument();
  });

  it("rejects a range whose minimum is above its maximum", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole("button", { name: "Specifications" }));
    const modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.selectOptions(within(modal).getByLabelText("Add specification"), "ram_gb");
    await user.click(within(modal).getByRole("button", { name: "Add filter" }));
    await user.type(within(modal).getByLabelText("Minimum RAM"), "16");
    await user.type(within(modal).getByLabelText("Maximum RAM"), "8");
    await user.click(within(modal).getByRole("button", { name: "Apply filters" }));

    expect(within(modal).getByRole("alert")).toHaveTextContent("minimum specification value");
  });
});

