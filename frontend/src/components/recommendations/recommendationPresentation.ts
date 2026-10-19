import type {
  EvidenceGrade,
  RecommendationVerdict,
} from "./types";

export type VerdictPresentation = {
  label: string;
  badgeClass: string;
  requiresAttention: boolean;
};

const VERDICT_PRESENTATIONS: Record<
  RecommendationVerdict,
  VerdictPresentation
> = {
  STRONG_UPGRADE_CANDIDATE: {
    label: "Strong upgrade candidate",
    badgeClass: "badge-error badge-soft",
    requiresAttention: true,
  },

  WORTH_CONSIDERING: {
    label: "Worth considering",
    badgeClass: "badge-warning badge-soft",
    requiresAttention: true,
  },

  WORTH_WATCHING: {
    label: "Worth watching",
    badgeClass: "badge-info badge-soft",
    requiresAttention: false,
  },

  NO_MEANINGFUL_CHANGE: {
    label: "No meaningful change",
    badgeClass: "badge-success badge-soft",
    requiresAttention: false,
  },
};

export const RECOMMENDATION_VERDICTS: readonly RecommendationVerdict[] = [
  "STRONG_UPGRADE_CANDIDATE",
  "WORTH_CONSIDERING",
  "WORTH_WATCHING",
  "NO_MEANINGFUL_CHANGE",
];

export function verdictPresentation(
  verdict: RecommendationVerdict
): VerdictPresentation {
  return VERDICT_PRESENTATIONS[verdict];
}

export function evidenceGradeText(
  grade: EvidenceGrade
): string {
  if (grade === "-") {
    return "Not enough evidence yet";
  }

  if (grade === null) {
    return "No evidence grade available";
  }

  return `Owner evidence: ${grade}`;
}

export function formatRecommendationPrice(
  price: number | null,
  currency: string | null
): string {
  if (price === null) {
    return "Price unavailable";
  }

  return new Intl.NumberFormat("en-SG", {
    style: "currency",
    currency: currency ?? "SGD",
  }).format(price);
}

/** DOM id of a recommendation's card, used to scroll to it. */
export function recommendationCardId(recommendationId: number) {
  return `recommendation-${recommendationId}`;
}
