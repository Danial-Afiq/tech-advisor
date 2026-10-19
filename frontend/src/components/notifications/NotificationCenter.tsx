import { useCallback, useEffect, useId, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  getUnreadNotificationCount,
  isNotificationsUnavailable,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "../../api/notifications";
import { DASHBOARD_PATH } from "../../routing/paths";
import { NotificationButton } from "../layout/NotificationButton";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { EmptyState } from "../ui/EmptyState";
import { FormError } from "../ui/Form";
import { LoadingBlock } from "../ui/LoadingBlock";
import {
  NOTIFICATION_POLL_MS,
  RECOMMENDATION_QUERY_PARAM,
  relativeTime,
} from "./notificationPresentation";
import type { AppNotification } from "./types";

/**
 * The top-bar bell: unread count (re-checked every 45s) plus a dropdown of the
 * signed-in user's notifications. Clicking one marks it read and takes the
 * user to its recommendation card on the dashboard. Self-contained, so any
 * signed-in USER page renders it as `topbarActions={<NotificationCenter />}`.
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
  const [items, setItems] = useState<AppNotification[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [markingAll, setMarkingAll] = useState(false);

  const refreshUnread = useCallback(() => {
    getUnreadNotificationCount()
      .then((count) => {
        setUnread(count);
        setUnavailable(false);
      })
      .catch((err: unknown) => {
        if (isNotificationsUnavailable(err)) setUnavailable(true);
        // Any other failure keeps the last known count; the page owns sign-out.
      });
  }, []);

  // Poll so a recommendation produced in the background shows up without a reload.
  useEffect(() => {
    refreshUnread();
    const timer = window.setInterval(refreshUnread, NOTIFICATION_POLL_MS);
    return () => window.clearInterval(timer);
  }, [refreshUnread]);

  /** Loads one page; page 0 replaces the list. */
  function loadPage(pageToLoad: number) {
    const request = ++requestRef.current;
    setLoading(true);
    setError(null);

    listNotifications(pageToLoad)
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

  async function handleOpen(notification: AppNotification) {
    if (!notification.read) {
      try {
        await markNotificationRead(notification.notificationId);
        setUnread((count) => Math.max(0, count - 1));
        setItems((current) =>
          current.map((item) =>
            item.notificationId === notification.notificationId ? { ...item, read: true } : item
          )
        );
      } catch {
        // Still take the user to the recommendation; it stays unread for next time.
      }
    }

    if (notification.available && notification.recommendationId !== null) {
      setOpen(false);
      void navigate(
        `${DASHBOARD_PATH}?${RECOMMENDATION_QUERY_PARAM}=${encodeURIComponent(
          String(notification.recommendationId)
        )}`
      );
    }
  }

  async function handleMarkAll() {
    setMarkingAll(true);
    setError(null);
    try {
      await markAllNotificationsRead();
      setUnread(0);
      setItems((current) => current.map((item) => ({ ...item, read: true })));
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
            <div className="card-body max-h-[calc(100dvh-120px)] gap-[12px] overflow-y-auto p-[18px]">
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
                  <FormError message={error} />

                  {items.length > 0 && (
                    <ul aria-label="Notification list" className="m-0 grid list-none gap-0 p-0">
                      {items.map((notification) => (
                        <NotificationItem
                          key={notification.notificationId}
                          notification={notification}
                          onOpen={() => void handleOpen(notification)}
                        />
                      ))}
                    </ul>
                  )}

                  {loading && <LoadingBlock label="Loading notifications…" />}

                  {!loading && items.length === 0 && !error && (
                    <EmptyState
                      icon="✦"
                      title="No notifications yet"
                      description="You’ll be notified here when a new or changed upgrade recommendation is found for one of your devices."
                    />
                  )}

                  {!loading && page + 1 < totalPages && (
                    <div className="border-t border-white/[0.09] pt-[10px] text-center">
                      <Button size="sm" variant="ghost" onClick={() => loadPage(page + 1)}>
                        View older notifications
                      </Button>
                    </div>
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
  onOpen,
}: {
  notification: AppNotification;
  onOpen: () => void;
}) {
  const canOpen = notification.available && notification.recommendationId !== null;

  return (
    <li className="border-b border-white/[0.09] last:border-b-0">
      <button
        type="button"
        aria-label={`${notification.read ? "" : "Unread: "}${notification.title}. ${notification.message}`}
        onClick={onOpen}
        className="flex w-full items-start gap-[12px] rounded-[13px] px-[8px] py-[12px] text-left hover:bg-white/[0.04]"
      >
        <span
          aria-hidden="true"
          className="grid h-[36px] w-[36px] shrink-0 place-items-center rounded-[10px] border border-white/[0.09] bg-[#0a1423] text-[16px]"
        >
          📱
        </span>

        <span className="min-w-0 flex-1">
          <span className={`block text-[14px] text-[#eef5ff] ${notification.read ? "font-semibold" : "font-extrabold"}`}>
            {notification.title}
          </span>
          <span className="mt-[2px] block text-[13px] leading-[1.45] text-[#c6d1df]">
            {notification.message}
          </span>
          {!canOpen && (
            <span className="mt-[4px] block text-[12px] text-[#8fa0b8]">
              This recommendation is no longer available.
            </span>
          )}
          <span className="mt-[4px] block text-[12px] text-[#8fa0b8]">
            {relativeTime(notification.createdAt)} · {notification.read ? "Read" : "Unread"}
          </span>
        </span>

        {!notification.read && (
          <span aria-hidden="true" className="mt-[6px] h-[9px] w-[9px] shrink-0 rounded-full bg-[#28c0ff]" />
        )}
      </button>
    </li>
  );
}
