/** Only one kind exists today; kept open so the API can add more later. */
export type NotificationType = "NEW_RECOMMENDATION" | (string & {});

/**
 * One in-app notification, as returned by `GET /api/notifications`.
 *
 * A notification groups the genuinely new or changed recommendations from one
 * evaluation of one device ("We found 3 new upgrade options for your iPhone
 * 13"). Title and message are written by the backend; the recommendation's
 * details stay in `recommendations` and are not copied here.
 */
export type AppNotification = {
  notificationId: number;
  type: NotificationType;
  title: string;
  message: string;
  /** The recommendation to open (the best one in the group); null once deleted. */
  recommendationId: number | null;
  /** False when that recommendation is no longer on the dashboard. */
  available: boolean;
  read: boolean;
  createdAt: string;
};

export type NotificationPage = {
  items: AppNotification[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};
