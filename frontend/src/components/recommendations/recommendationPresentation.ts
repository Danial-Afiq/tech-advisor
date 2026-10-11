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