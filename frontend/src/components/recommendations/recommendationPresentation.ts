import type {
  EvidenceGrade,
  RecommendationVerdict,
} from "./types";
import { formatDate } from "../devices/deviceOptions";

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

  const amount = new Intl.NumberFormat("en-SG", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(price);
  const code = currency?.trim().toUpperCase();
  if (!code || !/^[A-Z]{3}$/.test(code)) {
    return `${amount} (currency unavailable)`;
  }

  try {
    return new Intl.NumberFormat("en-SG", {
      style: "currency",
      currency: code,
      currencyDisplay: "code",
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(price);
  } catch {
    return `${code} ${amount}`;
  }
}

export function priceConversionNote(
  status: "ORIGINAL" | "CONVERTED" | "STALE" | "UNAVAILABLE",
  rateDate: string | null
): string | null {
  if (status === "STALE") {
    return rateDate
      ? `Using stale exchange rates dated ${formatDate(rateDate)}.`
      : "Using stale exchange rates.";
  }

  if (status === "UNAVAILABLE") {
    return "Currency conversion is unavailable; showing the original price.";
  }

  return null;
}
