import { apiFetch } from "./client";
import type { DashboardRecommendation } from "../components/recommendations/types";
import type { Currency } from "../components/currency/currency";

export type { DashboardRecommendation } from "../components/recommendations/types";

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
  const normalised = value?.trim().toLowerCase();

  return (
    SORT_OPTIONS.find((option) => option.value === normalised)?.value ??
    DEFAULT_SORT
  );
}

export function listDashboardRecommendations(
  sort: SortOption = DEFAULT_SORT,
  currency: Currency = "SGD"
) {
  const params = new URLSearchParams({ currency });
  if (sort !== DEFAULT_SORT) params.set("sort", sort);

  return apiFetch<DashboardRecommendation[]>(
    `/api/dashboard/recommendations?${params.toString()}`
  );
}

/** `PUT`/`DELETE /api/dashboard/recommendations/{id}/star` — saves or unsaves one recommendation. */
export function setRecommendationStarred(
  recommendationId: number,
  starred: boolean
) {
  return apiFetch<void>(
    `/api/dashboard/recommendations/${encodeURIComponent(recommendationId)}/star`,
    { method: starred ? "PUT" : "DELETE" }
  );
}
export type RecommendationDismissalReason =
  | "TOO_EXPENSIVE"
  | "MISSING_REQUIRED_FEATURE"
  | "NOT_ENOUGH_IMPROVEMENT"
  | "NOT_INTERESTED"
  | "OTHER";

export const DISMISSAL_REASON_OPTIONS: readonly {
  value: RecommendationDismissalReason;
  label: string;
}[] = [
  {
    value: "TOO_EXPENSIVE",
    label: "Too expensive",
  },
  {
    value: "MISSING_REQUIRED_FEATURE",
    label: "Missing a required feature",
  },
  {
    value: "NOT_ENOUGH_IMPROVEMENT",
    label: "Not enough improvement",
  },
  {
    value: "NOT_INTERESTED",
    label: "Not interested",
  },
  {
    value: "OTHER",
    label: "Other",
  },
];

export function dismissRecommendation(
  recommendationId: number,
  reason: RecommendationDismissalReason,
  comment?: string
) {
  return apiFetch<void>(
    `/api/dashboard/recommendations/${encodeURIComponent(recommendationId)}/dismiss`,
    {
      method: "POST",
      body: {
        reason,
        comment: comment?.trim() || undefined,
      },
    }
  );
}
