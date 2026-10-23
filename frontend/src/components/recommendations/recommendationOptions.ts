import type {
  DashboardRecommendation,
  FilterEvidenceGrade,
  RecommendationFilters,
} from "./types";
import {
  RECOMMENDATION_VERDICTS,
  verdictPresentation,
} from "./recommendationPresentation";

export const VERDICT_OPTIONS = RECOMMENDATION_VERDICTS.map((value) => ({
  value,
  label: verdictPresentation(value).label,
}));

export const EVIDENCE_GRADE_OPTIONS = [
  { value: "A", label: "A" },
  { value: "B", label: "B" },
  { value: "C", label: "C" },
  { value: "D", label: "D" },
  { value: "E", label: "E" },
  { value: "F", label: "F" },
] as const satisfies readonly {
  value: FilterEvidenceGrade;
  label: string;
}[];

export function emptyRecommendationFilters(): RecommendationFilters {
  return { deviceId: null, verdicts: [], evidenceGrades: [], savedOnly: false };
}

export function filterRecommendations(
  recommendations: readonly DashboardRecommendation[],
  filters: RecommendationFilters,
) {
  return recommendations.filter((recommendation) =>
    (filters.deviceId === null || recommendation.currentDeviceId === filters.deviceId)
    && (filters.verdicts.length === 0 || filters.verdicts.includes(recommendation.verdict))
    && (filters.evidenceGrades.length === 0
      || (recommendation.confidence !== null
        && recommendation.confidence !== "-"
        && filters.evidenceGrades.includes(recommendation.confidence)))
    && (!filters.savedOnly || recommendation.starred),
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

/** Counts the dropdown's own selections; the Saved toggle has its own visible state. */
export function selectedFilterCount(filters: RecommendationFilters) {
  return Number(filters.deviceId !== null) + filters.verdicts.length + filters.evidenceGrades.length;
}

/**
 * How many recommendations each owned device shows before "Show all". The
 * dashboard caps per device, so a user with more devices sees proportionally
 * more cards, and every device keeps its own top picks.
 */
export const TOP_RECOMMENDATIONS_PER_DEVICE = 3;

export type DeviceRecommendationGroup = {
  deviceId: number;
  deviceName: string;
  recommendations: DashboardRecommendation[];
};

/**
 * Groups recommendations by owned device. Devices appear in the order of
 * their first recommendation and rows keep their order, so the top of each
 * group is the top of the server's chosen sort.
 */
export function groupRecommendationsByDevice(
  recommendations: readonly DashboardRecommendation[],
): DeviceRecommendationGroup[] {
  const groups = new Map<number, DeviceRecommendationGroup>();
  recommendations.forEach((recommendation) => {
    const { currentDeviceId: deviceId, currentDeviceName: deviceName } = recommendation;
    const group = groups.get(deviceId) ?? { deviceId, deviceName, recommendations: [] };
    group.recommendations.push(recommendation);
    groups.set(deviceId, group);
  });
  return [...groups.values()];
}
