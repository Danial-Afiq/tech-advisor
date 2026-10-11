import { apiFetch } from "./client";
import type { DashboardRecommendation } from "../components/recommendations/types";

export type { DashboardRecommendation } from "../components/recommendations/types";

export function listDashboardRecommendations() {
  return apiFetch<DashboardRecommendation[]>(
    "/api/dashboard/recommendations"
  );
}