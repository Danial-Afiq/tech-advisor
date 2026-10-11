import { apiFetch } from "./client";

export type DashboardRecommendation = {
  recommendationId: number;
  currentDeviceId: number;
  currentDeviceName: string;
  candidateProductId: number;
  candidateBrand: string;
  candidateModelName: string;
  latestPrice: number | null;
  currency: string | null;
  verdict: string;
  confidence: string | null;
  reasoning: string | null;
  createdAt: string;
};

export const SORT_OPTIONS = [
  { value: "relevance", label: "Most relevant" },
  { value: "price_asc", label: "Price: low to high" },
  { value: "price_desc", label: "Price: high to low" },
  { value: "recency", label: "Newest first" },
] as const;

export type SortOption = (typeof SORT_OPTIONS)[number]["value"];

export const DEFAULT_SORT: SortOption = "relevance";

/** Falls back to the default for a missing or hand-edited `sort` in the URL. */
export function parseSort(value: string | null): SortOption {
  return (
    SORT_OPTIONS.find((option) => option.value === value)?.value ??
    DEFAULT_SORT
  );
}

export function listDashboardRecommendations(
  sort: SortOption = DEFAULT_SORT
) {
  const query =
    sort === DEFAULT_SORT ? "" : `?sort=${encodeURIComponent(sort)}`;

  return apiFetch<DashboardRecommendation[]>(
    `/api/dashboard/recommendations${query}`
  );
}