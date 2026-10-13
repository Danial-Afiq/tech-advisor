export type RecommendationVerdict =
  | "NO_MEANINGFUL_CHANGE"
  | "WORTH_WATCHING"
  | "WORTH_CONSIDERING"
  | "STRONG_UPGRADE_CANDIDATE";

export type EvidenceGrade =
  | "A"
  | "B"
  | "C"
  | "D"
  | "E"
  | "F"
  | "-"
  | null;

export type FilterEvidenceGrade = Exclude<EvidenceGrade, "-" | null>;

export type DashboardRecommendation = {
  recommendationId: number;
  currentDeviceId: number;
  currentDeviceName: string;
  candidateProductId: number;
  candidateBrand: string;
  candidateModelName: string;
  latestPrice: number | null;
  currency: string | null;
  verdict: RecommendationVerdict;
  confidence: EvidenceGrade;
  reasoning: string | null;
  createdAt: string;
};

export type RecommendationFilters = {
  deviceId: number | null;
  verdicts: RecommendationVerdict[];
  evidenceGrades: FilterEvidenceGrade[];
};
