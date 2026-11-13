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
    candidateSpecifications: { ram_gb: 8, os: "iOS" },
    latestPrice: 900,
    currency: "SGD",
    verdict: "WORTH_WATCHING",
    confidence: "C",
    reasoning: "Test",
    starred: false,
    createdAt: "2026-11-13T00:00:00Z",
  },
];

function Harness({
  recommendations = rows,
  initialFilters = emptyRecommendationFilters(),
}: {
  recommendations?: DashboardRecommendation[];
  initialFilters?: RecommendationFilters;
}) {
  const [filters, setFilters] = useState<RecommendationFilters>(initialFilters);
  const visible = filterRecommendations(recommendations, filters);
  return (
    <>
      <SpecificationFilterControl
        recommendations={recommendations}
        filters={filters}
        onChange={setFilters}
      />
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

  it("adds categorical values and removes empty drafts when applied", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole("button", { name: "Specifications" }));
    let modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.selectOptions(within(modal).getByLabelText("Add specification"), "os");
    await user.click(within(modal).getByRole("button", { name: "Add filter" }));
    await user.click(within(modal).getByRole("checkbox", { name: "Android" }));
    await user.click(within(modal).getByRole("button", { name: "Apply filters" }));

    expect(screen.getByRole("button", { name: "Specifications (1)" })).toBeInTheDocument();
    expect(screen.getByText("Twelve")).toBeInTheDocument();
    expect(screen.queryByText("Eight")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Specifications (1)" }));
    modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.click(within(modal).getByRole("checkbox", { name: "Android" }));
    await user.click(within(modal).getByRole("button", { name: "Apply filters" }));

    expect(screen.getByRole("button", { name: "Specifications" })).toBeInTheDocument();
    expect(screen.getByText("Twelve, Eight")).toBeInTheDocument();
  });

  it("removes a constraint, clears all devices, and cancels without applying", async () => {
    const user = userEvent.setup();
    const initialFilters: RecommendationFilters = {
      ...emptyRecommendationFilters(),
      specificationsByDevice: {
        10: [
          { kind: "numeric", spec: "ram_gb", min: 12, max: null },
          { kind: "categorical", spec: "os", values: ["Android"] },
        ],
      },
    };
    render(<Harness initialFilters={initialFilters} />);

    await user.click(screen.getByRole("button", { name: "Specifications (2)" }));
    let modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.click(within(modal).getByRole("button", { name: "Remove RAM filter" }));
    await user.click(within(modal).getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Specifications (2)" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Specifications (2)" }));
    modal = screen.getByRole("dialog", { name: "Specification filters" });
    await user.click(within(modal).getByRole("button", { name: "Clear all" }));
    await user.click(within(modal).getByRole("button", { name: "Apply filters" }));

    expect(screen.getByRole("button", { name: "Specifications" })).toBeInTheDocument();
    expect(screen.getByText("Twelve, Eight")).toBeInTheDocument();
  });

  it("switches device schemas and explains unsupported categories", async () => {
    const user = userEvent.setup();
    const gpu: DashboardRecommendation = {
      ...rows[0],
      recommendationId: 3,
      currentDeviceId: 11,
      currentDeviceName: "My GPU",
      candidateProductId: 30,
      candidateModelName: "Graphics card",
      candidateCategory: "GPU",
      candidateSpecifications: { vram: 16, memory_type: "GDDR6" },
    };
    const unsupported: DashboardRecommendation = {
      ...rows[0],
      recommendationId: 4,
      currentDeviceId: 12,
      currentDeviceName: "My laptop",
      candidateProductId: 40,
      candidateModelName: "Laptop",
      candidateCategory: "LAPTOP",
      candidateSpecifications: {},
    };
    render(<Harness recommendations={[...rows, gpu, unsupported]} />);

    await user.click(screen.getByRole("button", { name: "Specifications" }));
    const modal = screen.getByRole("dialog", { name: "Specification filters" });
    const device = within(modal).getByLabelText("Owned device");
    await user.selectOptions(device, "11");
    expect(within(modal).getByRole("option", { name: /VRAM/ })).toBeInTheDocument();

    await user.selectOptions(device, "12");
    expect(within(modal).getByText(
      "Specification filtering is not available for this device category.",
    )).toBeInTheDocument();
  });

  it("handles an empty recommendation list", async () => {
    const user = userEvent.setup();
    render(<Harness recommendations={[]} />);

    await user.click(screen.getByRole("button", { name: "Specifications" }));
    const modal = screen.getByRole("dialog", { name: "Specification filters" });
    expect(within(modal).getByText(
      "Specification filtering is not available for this device category.",
    )).toBeInTheDocument();
    expect(within(modal).getByRole("button", { name: "Clear all" })).toBeDisabled();
  });
});

