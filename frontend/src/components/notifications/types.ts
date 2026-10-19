import type {
  EvidenceGrade,
  RecommendationVerdict,
} from "../recommendations/types";

/**
 * Confidence tier stored on each notification. The backend works it out from
 * the verdict and evidence grade (rule and cut-offs in config); HIGH is the
 * same rule as email eligibility. The frontend only displays it.
 */
export type NotificationTier = "HIGH" | "MEDIUM" | "LOW";

/**
 * One in-app notification, as returned by `GET /api/notifications`.
 *
 * PLANNED CONTRACT: the backend for this does not exist yet (ticket 4.4.2).
 * Product, verdict and grade are a snapshot taken when the recommendation was
 * saved, so the notification still reads correctly after the recommendation
 * changes.
 */
export type AppNotification = {
  notificationId: number;
  /** `null` once the recommendation row has been deleted. */
  recommendationId: number | null;
  currentDeviceName: string;
  candidateBrand: string;
  candidateModelName: string;
  verdict: RecommendationVerdict;
  evidenceGrade: EvidenceGrade;
  tier: NotificationTier;
  read: boolean;
  /** False once the recommendation is superseded, expired or removed. */
  actionable: boolean;
  createdAt: string;
};

export type NotificationReadFilter = "all" | "unread" | "read";

export type NotificationQuery = {
  read: NotificationReadFilter;
  tier: NotificationTier | null;
  page: number;
  size: number;
};

export type NotificationPage = {
  items: AppNotification[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};
