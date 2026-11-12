import { describe, expect, it } from "vitest";
import {
  emptyRecommendationFilters,
  filterRecommendations,
  matchesSpecificationFilters,
} from "./recommendationOptions";
import { specificationFilterFields } from "./specificationFilterOptions";
import type { DashboardRecommendation, RecommendationFilters } from "./types";

function recommendation(
  recommendationId: number,
  currentDeviceId: number,
  specifications: Record<string, string | number>,
): DashboardRecommendation {
  return {
    recommendationId,
    currentDeviceId,
    currentDeviceName: `Device ${currentDeviceId}`,
    candidateProductId: recommendationId + 100,
    candidateBrand: "Example",
    candidateModelName: `Candidate ${recommendationId}`,
    candidateCategory: "SMARTPHONE",
    candidateSpecifications: specifications,
    latestPrice: 1000,
    currency: "SGD",
    verdict: "WORTH_CONSIDERING",
    confidence: "B",
    reasoning: "Test recommendation",
    starred: false,
    createdAt: "2026-11-13T00:00:00Z",
  };
}

function withSpecifications(
  specificationsByDevice: RecommendationFilters["specificationsByDevice"],
) {
  return { ...emptyRecommendationFilters(), specificationsByDevice };
}

describe("deterministic specification filters", () => {
  it("derives category-specific API keys and filter kinds from the shared schemas", () => {
    expect(specificationFilterFields("SMARTPHONE")).toEqual(expect.arrayContaining([
      expect.objectContaining({ spec: "ram_gb", kind: "numeric", unit: "GB" }),
      expect.objectContaining({ spec: "display_size_inches", kind: "numeric", unit: "in" }),
      expect.objectContaining({ spec: "os", kind: "categorical" }),
    ]));
    expect(specificationFilterFields("SMARTPHONE").some((field) => field.spec === "camera_specs"))
      .toBe(false);
    expect(specificationFilterFields("GPU")).toEqual(expect.arrayContaining([
      expect.objectContaining({ spec: "vram", kind: "numeric", unit: "GB" }),
      expect.objectContaining({ spec: "memory_type", kind: "categorical" }),
      expect.objectContaining({ spec: "tdp_watts", kind: "numeric", unit: "W" }),
    ]));
  });

  it("supports inclusive minimum, maximum and range constraints", () => {
    const row = recommendation(1, 10, { ram_gb: 12, display_size_inches: 6.5 });

    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [
        { kind: "numeric", spec: "ram_gb", min: 12, max: 16 },
        { kind: "numeric", spec: "display_size_inches", min: 6.5, max: 6.5 },
      ],
    }))).toBe(true);
    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [{ kind: "numeric", spec: "ram_gb", min: 13, max: null }],
    }))).toBe(false);
    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [{ kind: "numeric", spec: "ram_gb", min: null, max: 11 }],
    }))).toBe(false);
  });

  it("fails closed when an active specification value is unknown", () => {
    const row = recommendation(1, 10, {});
    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [{ kind: "numeric", spec: "ram_gb", min: 8, max: null }],
    }))).toBe(false);
  });

  it("matches selected text values deterministically without case or whitespace drift", () => {
    const row = recommendation(1, 10, { os: " Android " });
    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [{ kind: "categorical", spec: "os", values: ["android"] }],
    }))).toBe(true);
  });

  it("scopes constraints to one owned device and preserves the returned order", () => {
    const first = recommendation(1, 10, { ram_gb: 8 });
    const second = recommendation(2, 11, { ram_gb: 4 });
    const third = recommendation(3, 10, { ram_gb: 16 });

    expect(filterRecommendations([first, second, third], withSpecifications({
      10: [{ kind: "numeric", spec: "ram_gb", min: 12, max: null }],
    }))).toEqual([second, third]);
  });

  it("treats blank draft constraints as optional no-ops", () => {
    const row = recommendation(1, 10, {});
    expect(matchesSpecificationFilters(row, withSpecifications({
      10: [
        { kind: "numeric", spec: "ram_gb", min: null, max: null },
        { kind: "categorical", spec: "os", values: [] },
      ],
    }))).toBe(true);
  });
});

