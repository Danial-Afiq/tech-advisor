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

export function listDashboardRecommendations() {
  return apiFetch<DashboardRecommendation[]>(
    "/api/dashboard/recommendations"
  );
}