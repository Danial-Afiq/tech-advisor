import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  NOTIFICATION_PAGE_SIZE,
  dismissNotification,
  getUnreadNotificationCount,
  isNotificationsUnavailable,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "../../api/notifications";
import { DASHBOARD_PATH } from "../../routing/paths";
import { formatDate } from "../devices/deviceOptions";
import { NotificationButton } from "../layout/NotificationButton";
import {
  evidenceGradeText,
  verdictPresentation,
} from "../recommendations/recommendationPresentation";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { EmptyState } from "../ui/EmptyState";
import { Field, FormError, Select } from "../ui/Form";
import { LoadingBlock } from "../ui/LoadingBlock";
import {
  READ_FILTER_OPTIONS,
  RECOMMENDATION_QUERY_PARAM,
  TIER_FILTER_OPTIONS,
  notificationProductName,
  parseTierFilter,
  tierPresentation,
} from "./notificationPresentation";
import type {
  AppNotification,
  NotificationReadFilter,
  NotificationTier,
} from "./types";

/**
 * The top-bar bell: live unread count plus a dropdown to list, filter, open,
 * mark read and dismiss the signed-in user's notifications. Self-contained, so
 * any signed-in USER page renders it as `topbarActions={<NotificationCenter />}`.
 */
export function NotificationCenter() {
  const navigate = useNavigate();
  const id = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const requestRef = useRef(0);

  const [open, setOpen] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [unread, setUnread] = useState(0);
  const [readFilter, setReadFilter] = useState<NotificationReadFilter>("all");
  const [tier, setTier] = useState<NotificationTier | null>(null);
  const [items, setItems] = useState<AppNotification[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyIds, setBusyIds] = useState<ReadonlySet<number>>(() => new Set());
  const [markingAll, setMarkingAll] = useState(false);

  const refreshUnread = useCallback(() => {
    getUnreadNotificationCount()
      .then((count) => {
        setUnread(count);
        setUnavailable(false);
      })
      .catch((err: unknown) => {
        if (isNotificationsUnavailable(err)) setUnavailable(true);
        // Any other failure keeps the last known count; the page owns 401s.
      });
  }, []);

  useEffect(refreshUnread, [refreshUnread]);

  /** Loads one page for the given filters; page 0 replaces the list. */
  function loadPage(
    pageToLoad: number,
    nextRead: NotificationReadFilter = readFilter,
    nextTier: NotificationTier | null = tier
  ) {
    const request = ++requestRef.current;
    setLoading(true);
    setError(null);

    listNotifications({
      read: nextRead,
      tier: nextTier,
      page: pageToLoad,
      size: NOTIFICATION_PAGE_SIZE,
    })
      .then((result) => {
        if (request !== requestRef.current) return;
        setItems((current) =>
          pageToLoad === 0 ? result.items : [...current, ...result.items]
        );
        setPage(result.page);
        setTotalPages(result.totalPages);
        setUnavailable(false);
      })
      .catch((err: unknown) => {
        if (request !== requestRef.current) return;
        if (isNotificationsUnavailable(err)) {
          setUnavailable(true);
        } else {
          setError(err instanceof Error ? err.message : "Couldn’t load notifications.");
        }
      })
      .finally(() => {
        if (request === requestRef.current) setLoading(false);
      });
  }

  useEffect(() => {
    if (!open) return;

    function handlePointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) {
        setOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
      }
    }

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  async function runForItem(notification: AppNotification, action: () => Promise<void>) {
    const itemId = notification.notificationId;
    setBusyIds((current) => new Set(current).add(itemId));
    setError(null);
    try {
      await action();
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : "Please try again.");
      return false;
    } finally {
      setBusyIds((current) => {
        const next = new Set(current);
        next.delete(itemId);
        return next;
      });
    }
  }

  function applyRead(notification: AppNotification) {
    if (notification.read) return;
    setUnread((count) => Math.max(0, count - 1));
    setItems((current) =>
      readFilter === "unread"
        ? current.filter((item) => item.notificationId !== notification.notificationId)
        : current.map((item) =>
            item.notificationId === notification.notificationId ? { ...item, read: true } : item
          )
    );
  }

  async function handleMarkRead(notification: AppNotification) {
    if (await runForItem(notification, () => markNotificationRead(notification.notificationId))) {
      applyRead(notification);
    }
  }

  async function handleDismiss(notification: AppNotification) {
    if (await runForItem(notification, () => dismissNotification(notification.notificationId))) {
      if (!notification.read) setUnread((count) => Math.max(0, count - 1));
      setItems((current) =>
        current.filter((item) => item.notificationId !== notification.notificationId)
      );
    }
  }

  async function handleOpen(notification: AppNotification) {
    if (!notification.read) {
      // Opening still navigates if marking read fails; the error stays visible next time.
      if (await runForItem(notification, () => markNotificationRead(notification.notificationId))) {
        applyRead(notification);
      }
    }

    setOpen(false);
    void navigate(
      `${DASHBOARD_PATH}?${RECOMMENDATION_QUERY_PARAM}=${encodeURIComponent(
        String(notification.recommendationId)
      )}`
    );
  }

  async function handleMarkAll() {
    setMarkingAll(true);
    setError(null);
    try {
      await markAllNotificationsRead();
      setUnread(0);
      setItems((current) =>
        readFilter === "unread" ? [] : current.map((item) => ({ ...item, read: true }))
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Please try again.");
    } finally {
      setMarkingAll(false);
    }
  }

  return (
    <div
      ref={rootRef}
      className="relative"
      onBlur={(event) => {
        if (event.relatedTarget instanceof Node && !event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false);
        }
      }}
    >
      <NotificationButton
        ref={triggerRef}
        unread={unavailable ? 0 : unread}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={`${id}-panel`}
        onClick={() => {
          if (!open) {
            refreshUnread();
            loadPage(0);
          }
          setOpen(!open);
        }}
      />

      {open && (
        <div
          id={`${id}-panel`}
          role="dialog"
          aria-labelledby={`${id}-title`}
          className="absolute right-0 z-20 mt-2 w-[400px] max-[620px]:fixed max-[620px]:inset-x-4 max-[620px]:top-[72px] max-[620px]:w-auto"
        >
          <Card className="bg-[#0d1727] text-left">
            <div className="card-body max-h-[calc(100dvh-120px)] gap-[14px] overflow-y-auto p-[18px]">
              <div className="flex items-center justify-between gap-3">
                <h3 id={`${id}-title`} className="m-0 text-[16px] font-extrabold text-[#eef5ff]">
                  Notifications
                </h3>
                {!unavailable && (
                  <Button
                    size="sm"
                    variant="ghost"
                    loading={markingAll}
                    disabled={unread === 0}
                    onClick={() => void handleMarkAll()}
                  >
                    Mark all as read
                  </Button>
                )}
              </div>

              {unavailable ? (
                <EmptyState
                  icon="🔔"
                  title="Notifications aren’t available yet"
                  description="This server doesn’t support in-app notifications yet. Your recommendations are still on the dashboard."
                />
              ) : (
                <>
                  <div className="grid grid-cols-2 gap-[10px] [&>*]:mb-0">
                    <Field label="Show" htmlFor={`${id}-read`}>
                      <Select
                        id={`${id}-read`}
                        value={readFilter}
                        options={READ_FILTER_OPTIONS}
                        onChange={(event) => {
                          const next = event.target.value as NotificationReadFilter;
                          setReadFilter(next);
                          loadPage(0, next, tier);
                        }}
                      />
                    </Field>
                    <Field label="Tier" htmlFor={`${id}-tier`}>
                      <Select
                        id={`${id}-tier`}
                        value={tier ?? ""}
                        options={TIER_FILTER_OPTIONS}
                        onChange={(event) => {
                          const next = parseTierFilter(event.target.value);
                          setTier(next);
                          loadPage(0, readFilter, next);
                        }}
                      />
                    </Field>
                  </div>

                  <FormError message={error} />

                  {items.length > 0 && (
                    <ul aria-label="Notification list" className="m-0 grid list-none gap-[10px] p-0">
                      {items.map((notification) => (
                        <NotificationItem
                          key={notification.notificationId}
                          notification={notification}
                          busy={busyIds.has(notification.notificationId)}
                          onOpen={() => void handleOpen(notification)}
                          onMarkRead={() => void handleMarkRead(notification)}
                          onDismiss={() => void handleDismiss(notification)}
                        />
                      ))}
                    </ul>
                  )}

                  {loading && <LoadingBlock label="Loading notifications…" />}

                  {!loading && items.length === 0 && !error && (
                    <EmptyState
                      icon="✦"
                      title={readFilter === "all" && tier === null ? "No notifications yet" : "No notifications match"}
                      description={
                        readFilter === "all" && tier === null
                          ? "You’ll be notified here whenever a new recommendation is produced for one of your devices."
                          : "Try another filter to see more notifications."
                      }
                    />
                  )}

                  {!loading && page + 1 < totalPages && (
                    <Button size="sm" onClick={() => loadPage(page + 1)}>
                      Load more
                    </Button>
                  )}
                </>
              )}
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}

function NotificationItem({
  notification,
  busy,
  onOpen,
  onMarkRead,
  onDismiss,
}: {
  notification: AppNotification;
  busy: boolean;
  onOpen: () => void;
  onMarkRead: () => void;
  onDismiss: () => void;
}) {
  const product = notificationProductName(notification);
  const verdict = verdictPresentation(notification.verdict);
  const tier = tierPresentation(notification.tier);
  const canOpen = notification.actionable && notification.recommendationId !== null;

  return (
    <li
      aria-label={`${notification.read ? "" : "Unread: "}${product} for ${notification.currentDeviceName}`}
      className={`rounded-[13px] border border-white/[0.09] p-[12px] ${
        notification.read ? "bg-[#0a1423]" : "bg-[#111d31]"
      } ${canOpen ? "" : "opacity-70"}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h4 className="m-0 flex items-center gap-[7px] text-[14px] font-bold text-[#eef5ff]">
            {!notification.read && (
              <span aria-hidden="true" className="h-[8px] w-[8px] shrink-0 rounded-full bg-[#7c5cff]" />
            )}
            {product}
          </h4>
          <p className="mt-[2px] mb-0 text-[12px] text-[#8fa0b8]">
            For {notification.currentDeviceName} · {formatDate(notification.createdAt)}
          </p>
        </div>
      </div>

      <div className="mt-[8px] flex flex-wrap items-center gap-[6px]">
        <span className={`badge rounded-full text-[10px] font-semibold ${verdict.badgeClass}`}>
          {verdict.label}
        </span>
        <span className={`badge rounded-full text-[10px] font-semibold ${tier.badgeClass}`}>
          {tier.label}
        </span>
        <span className="text-[11px] text-[#9eacc0]">
          {evidenceGradeText(notification.evidenceGrade)}
        </span>
      </div>

      {!canOpen && (
        <p className="mt-[8px] mb-0 text-[11px] text-[#8fa0b8]">
          This recommendation has since been updated or removed.
        </p>
      )}

      <div className="mt-[10px] flex flex-wrap gap-[6px]">
        {canOpen && (
          <Button size="sm" variant="secondary" disabled={busy} onClick={onOpen}>
            View recommendation <span aria-hidden="true">→</span>
          </Button>
        )}
        {!notification.read && (
          <Button size="sm" variant="ghost" disabled={busy} onClick={onMarkRead}>
            Mark as read
          </Button>
        )}
        <Button
          size="sm"
          variant="ghost"
          disabled={busy}
          aria-label={`Dismiss ${product} for ${notification.currentDeviceName}`}
          onClick={onDismiss}
        >
          Dismiss
        </Button>
      </div>
    </li>
  );
}
