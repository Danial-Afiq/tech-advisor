import type { DashboardRecommendation } from "../../api/dashboard";
import type { RecommendationFilters } from "./types";

export const VERDICT_OPTIONS = [
  { value: "STRONG_UPGRADE_CANDIDATE", label: "Strong upgrade candidate" },
  { value: "WORTH_CONSIDERING", label: "Worth considering" },
  { value: "WORTH_WATCHING", label: "Worth watching" },
  { value: "NO_MEANINGFUL_CHANGE", label: "No meaningful change" },
] as const;

export const EVIDENCE_GRADE_OPTIONS = ["A", "B", "C", "D", "E", "F"]
  .map((grade) => ({ value: grade, label: grade }));

export function emptyRecommendationFilters(): RecommendationFilters {
  return { deviceId: null, verdicts: [], evidenceGrades: [] };
}

export function filterRecommendations(
  recommendations: readonly DashboardRecommendation[],
  filters: RecommendationFilters,
) {
  return recommendations.filter((recommendation) =>
    (filters.deviceId === null || recommendation.currentDeviceId === filters.deviceId)
    && (filters.verdicts.length === 0 || filters.verdicts.includes(recommendation.verdict))
    && (filters.evidenceGrades.length === 0
      || (recommendation.confidence !== null && filters.evidenceGrades.includes(recommendation.confidence))),
  );
}

/** Use IDs so two owned devices with the same display name stay distinct. */
export function recommendationDeviceOptions(recommendations: readonly DashboardRecommendation[]) {
  const devices = new Map<number, string>();
  recommendations.forEach(({ currentDeviceId, currentDeviceName }) => {
    devices.set(currentDeviceId, currentDeviceName);
  });
  const names = [...devices.values()];
  return [...devices.entries()]
    .sort(([, first], [, second]) => first.localeCompare(second))
    .map(([id, name]) => ({
      value: String(id),
      label: names.filter((other) => other === name).length > 1 ? `${name} (device ${id})` : name,
    }));
}

export function selectedFilterCount(filters: RecommendationFilters) {
  return Number(filters.deviceId !== null) + filters.verdicts.length + filters.evidenceGrades.length;
}

