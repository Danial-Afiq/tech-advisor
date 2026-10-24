import { useEffect, useState, type SubmitEvent } from "react";
import { useCurrency } from "../components/currency/currency";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  DEFAULT_SORT,
  DISMISSAL_REASON_OPTIONS,
  SORT_OPTIONS,
  dismissRecommendation,
  listDashboardRecommendations,
  parseSort,
  setRecommendationStarred,
  type DashboardRecommendation,
  type RecommendationDismissalReason,
  type SortOption,
} from "../api/dashboard";
import { signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { getSession } from "../api/session";
import { AppShell } from "../components/layout/AppShell";
import type { NavKey } from "../components/layout/Sidebar";
import { NotificationCenter } from "../components/notifications/NotificationCenter";
import { RECOMMENDATION_QUERY_PARAM } from "../components/notifications/notificationPresentation";
import { Button } from "../components/ui/Button";
import { Card } from "../components/ui/Card";
import { Callout } from "../components/ui/Callout";
import {Field, FormError, Select, Textarea,} from "../components/ui/Form";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { Modal, ModalActions } from "../components/ui/Modal";
import { ToastStack } from "../components/ui/ToastStack";
import { useToasts } from "../components/ui/useToasts";
import { PageHeader } from "../components/ui/PageHeader";
import { EmptyState } from "../components/ui/EmptyState";
import { RecommendationFilterDropdown } from "../components/recommendations/RecommendationFilterDropdown";
import {
  TOP_RECOMMENDATIONS_PER_DEVICE,
  emptyRecommendationFilters,
  filterRecommendations,
  groupRecommendationsByDevice,
} from "../components/recommendations/recommendationOptions";
import { SpecComparison } from "../components/recommendations/SpecComparison";
import { useComparisonSpecs } from "../components/recommendations/useComparisonSpecs";
import {
  DASHBOARD_PATH,
  DEVICES_PATH,
  LOGIN_PATH,
} from "../routing/paths";
import { RecommendationCard } from "../components/recommendations/RecommendationCard";
import {
  recommendationCardId,
  verdictPresentation,
} from "../components/recommendations/recommendationPresentation";

export default function DashboardPage() {
  const navigate = useNavigate();
  const session = getSession();
  const { currency } = useCurrency();

  /*
   * Sort lives in the URL independently from the local filters, so changing
   * either control does not reset the other and refreshes keep the same order.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = parseSort(searchParams.get("sort"));
  // Set when the user opens a notification; that card is outlined and scrolled to.
  const highlightParam = searchParams.get(RECOMMENDATION_QUERY_PARAM);
  const highlightId =
    highlightParam !== null && /^\d+$/.test(highlightParam)
      ? Number(highlightParam)
      : null;

  const [recommendations, setRecommendations] = useState<
    DashboardRecommendation[]
  >([]);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState(emptyRecommendationFilters);
  // Devices whose "Show all" is on; the rest show their top picks only.
  const [expandedDevices, setExpandedDevices] = useState<ReadonlySet<number>>(
    () => new Set()
  );
  const specs = useComparisonSpecs();
  const [pendingUnstar, setPendingUnstar] =
    useState<DashboardRecommendation | null>(null);
  const [pendingDismissal, setPendingDismissal] =
    useState<DashboardRecommendation | null>(null);
  const [dismissalReason, setDismissalReason] =
    useState<RecommendationDismissalReason>("NOT_INTERESTED");
  const [dismissalComment, setDismissalComment] =
    useState("");
  const [dismissalPending, setDismissalPending] =
    useState(false);
  const [dismissalError, setDismissalError] =
    useState<string | null>(null);
  const [starPendingIds, setStarPendingIds] = useState<ReadonlySet<number>>(
    () => new Set()
  );
  const { toasts, pushToast } = useToasts();
  // Bumped to refetch the current sort, e.g. after a row was replaced server-side.
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    if (loading || highlightId === null) return;
    document
      .getElementById(recommendationCardId(highlightId))
      ?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, [loading, highlightId, filters]);

  useEffect(() => {
    let cancelled = false;

    listDashboardRecommendations(sort, currency)
      .then((rows) => {
        if (!cancelled) {
          setRecommendations(rows);
          setError(null);
        }
      })
      .catch((err: Error) => {
        if (cancelled) {
          return;
        }

        if (err instanceof ApiError && err.status === 401) {
          void navigate(LOGIN_PATH, { replace: true });
          return;
        }

        setError(err.message);
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
          setRefreshing(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [navigate, sort, reloadCount, currency]);

  if (!session) {
    return null;
  }

  /*
   * Store one attention result per device.
   *
   * A device may have multiple recommendations. If at least one of
   * those recommendations requires attention, the device is counted
   * once under "Devices requiring attention".
   */
  const deviceAttention = new Map<number, boolean>();

  recommendations.forEach((recommendation) => {
    const requiresAttention =
      verdictPresentation(
        recommendation.verdict
      ).requiresAttention;

    const existingOutcome =
      deviceAttention.get(recommendation.currentDeviceId) ?? false;

    deviceAttention.set(
      recommendation.currentDeviceId,
      existingOutcome || requiresAttention
    );
  });

  const deviceCount = deviceAttention.size;

  const attentionCount = Array.from(
    deviceAttention.values()
  ).filter(Boolean).length;

  const noUpgradeCount = deviceCount - attentionCount;
  const filteredRecommendations = filterRecommendations(
    recommendations,
    filters
  );

  const deviceGroups = groupRecommendationsByDevice(filteredRecommendations);

  const toggleDeviceExpanded = (deviceId: number) =>
    setExpandedDevices((current) => {
      const next = new Set(current);
      if (next.has(deviceId)) next.delete(deviceId);
      else next.add(deviceId);
      return next;
    });

  const savedCount = recommendations.filter(
    (recommendation) => recommendation.starred
  ).length;

  const candidateName = (recommendation: DashboardRecommendation) =>
    [recommendation.candidateBrand, recommendation.candidateModelName]
      .filter(Boolean)
      .join(" ");

  const saveStar = async (
    recommendation: DashboardRecommendation,
    starred: boolean
  ) => {
    const id = recommendation.recommendationId;
    setStarPendingIds((current) => new Set(current).add(id));

    try {
      await setRecommendationStarred(id, starred);
      setRecommendations((current) =>
        current.map((row) =>
          row.recommendationId === id ? { ...row, starred } : row
        )
      );
      pushToast(
        starred ? "Recommendation saved" : "Removed from saved",
        `${candidateName(recommendation)} for ${recommendation.currentDeviceName}.`,
        starred ? "★" : "✓"
      );
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        void navigate(LOGIN_PATH, { replace: true });
        return;
      }

      /*
       * A re-assessment replaces the row (new id) while the page is open, so
       * the id we hold no longer exists. Reload to pick up the replacement,
       * which keeps any star the user already had.
       */
      if (err instanceof ApiError && err.status === 404) {
        setRefreshing(true);
        setReloadCount((count) => count + 1);
        pushToast(
          "Recommendation was just updated",
          "Your dashboard has been refreshed with the latest results. Please try again.",
          "!"
        );
        return;
      }

      pushToast(
        starred
          ? "Couldn’t save recommendation"
          : "Couldn’t remove saved recommendation",
        err instanceof Error ? err.message : "Please try again.",
        "!"
      );
    } finally {
      setStarPendingIds((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    }
  };

  // Saving is immediate; removing a saved recommendation is confirmed first.
  const handleToggleStar = (recommendation: DashboardRecommendation) => {
    if (recommendation.starred) {
      setPendingUnstar(recommendation);
    } else {
      void saveStar(recommendation, true);
    }
  };

  const confirmUnstar = () => {
    if (pendingUnstar) {
      void saveStar(pendingUnstar, false);
    }

    setPendingUnstar(null);
  };

  const openDismissal = (
    recommendation: DashboardRecommendation
  ) => {
    setPendingDismissal(recommendation);
    setDismissalReason("NOT_INTERESTED");
    setDismissalComment("");
    setDismissalError(null);
  };

  const closeDismissal = () => {
    if (!dismissalPending) {
      setPendingDismissal(null);
      setDismissalError(null);
    }
  };

  const submitDismissal = async (
    event: SubmitEvent<HTMLFormElement>
  ) => {
    event.preventDefault();

    if (!pendingDismissal) {
      return;
    }

    const recommendation = pendingDismissal;
    const id = recommendation.recommendationId;

    setDismissalPending(true);
    setDismissalError(null);

    try {
      await dismissRecommendation(
        id,
        dismissalReason,
        dismissalComment
      );

      setRecommendations((current) =>
        current.filter(
          (row) => row.recommendationId !== id
        )
      );

      setPendingDismissal(null);

      pushToast(
        "Recommendation dismissed",
        `${candidateName(recommendation)} has been removed from your dashboard.`,
        "✓"
      );
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        void navigate(LOGIN_PATH, { replace: true });
        return;
      }

      if (err instanceof ApiError && err.status === 404) {
        setPendingDismissal(null);
        setRefreshing(true);
        setReloadCount((count) => count + 1);

        pushToast(
          "Recommendation was just updated",
          "Your dashboard has been refreshed with the latest recommendations.",
          "!"
        );
        return;
      }

      setDismissalError(
        err instanceof Error
          ? err.message
          : "The recommendation could not be dismissed."
      );
    } finally {
      setDismissalPending(false);
    }
  };

  const handleSortChange = (newSort: SortOption) => {
    // Copy every existing query parameter and change only `sort`.
    const next = new URLSearchParams(searchParams);

    if (newSort === DEFAULT_SORT) {
      next.delete("sort");
    } else {
      next.set("sort", newSort);
    }

    // Page N of a different order is meaningless; filters are untouched.
    next.delete("page");

    setRefreshing(true);
    setSearchParams(next);
  };

  const handleNavigate = (key: NavKey) => {
    if (key === "dashboard") {
      void navigate(DASHBOARD_PATH);
    }

    if (key === "devices") {
      void navigate(DEVICES_PATH);
    }
  };

  const handleSignOut = () => {
    signOut();
    void navigate(LOGIN_PATH, { replace: true });
  };

  return (
    <AppShell
      active="dashboard"
      title="Dashboard"
      user={{
        name: session.email.split("@")[0],
        email: session.email,
      }}
      topbarActions={
        <NotificationCenter />
      }
      onNavigate={handleNavigate}
      onSignOut={handleSignOut}
    >
      <PageHeader
        eyebrow="Upgrade overview"
        title="Your recommendations"
        description="The verdict is our analysis of specs, price and your budget. The owner evidence grade (A–F) is separate: it summarises what owners of the suggested phone report."
      />

      {!loading &&
        !error &&
        recommendations.length > 0 && (
          <section
            aria-label="Recommendation summary"
            className="mb-5 grid grid-cols-3 gap-4 max-[620px]:gap-2"
          >
            <Card className="p-5 max-[620px]:p-3">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase max-[620px]:text-[10px] max-[620px]:tracking-[0.04em]">
                Active recommendations
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#eef5ff] max-[620px]:text-[22px]">
                {recommendations.length}
              </p>
            </Card>

            <Card className="p-5 max-[620px]:p-3">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase max-[620px]:text-[10px] max-[620px]:tracking-[0.04em]">
                Devices requiring attention
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#f8d77d] max-[620px]:text-[22px]">
                {attentionCount}
              </p>
            </Card>

            <Card className="p-5 max-[620px]:p-3">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase max-[620px]:text-[10px] max-[620px]:tracking-[0.04em]">
                No immediate upgrade
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#83e4c1] max-[620px]:text-[22px]">
                {noUpgradeCount}
              </p>
            </Card>
          </section>
        )}

      {loading && (
        <LoadingBlock label="Loading your recommendations…" />
      )}

      {!loading && error && (
        <Callout>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span>
              <b>We couldn’t load your dashboard.</b> {error}
            </span>

            <Button
              size="sm"
              variant="primary"
              onClick={() => window.location.reload()}
            >
              Try again
            </Button>
          </div>
        </Callout>
      )}

      {!loading &&
        !error &&
        recommendations.length === 0 && (
          <EmptyState
            icon="✦"
            title="No active recommendations yet"
            description="Recommendations will appear here when an upgrade outcome is available for one of your registered devices."
            action={
              <Button variant="primary" onClick={() => void navigate(DEVICES_PATH)}>
                View my devices
              </Button>
            }
          />
        )}

      {!loading && !error && recommendations.length > 0 && (
        <section
          aria-label="Recommendation controls"
          aria-busy={refreshing}
          className="mb-[14px] flex flex-wrap items-center justify-between gap-[10px]"
        >
          <p role="status" className="m-0 text-[13px] text-[#8fa0b8]">
            Showing {filteredRecommendations.length} of {recommendations.length} recommendations
          </p>

          <div className="flex flex-wrap items-center justify-end gap-[10px] max-[620px]:w-full max-[620px]:justify-stretch">
            <Button
              aria-pressed={filters.savedOnly}
              variant={filters.savedOnly ? "primary" : "secondary"}
              onClick={() =>
                setFilters({ ...filters, savedOnly: !filters.savedOnly })
              }
            >
              <span aria-hidden="true">{filters.savedOnly ? "★" : "☆"}</span>
              Saved ({savedCount})
            </Button>

            <RecommendationFilterDropdown
              recommendations={recommendations}
              filters={filters}
              onChange={setFilters}
            />

            <label className="flex items-center gap-[8px] text-[13px] font-semibold text-[#c6d1df] max-[620px]:w-full">
              <span className="shrink-0">Sort by</span>
              <Select
                aria-label="Sort by"
                className="w-auto max-[620px]:min-w-0 max-[620px]:flex-1"
                options={SORT_OPTIONS}
                value={sort}
                onChange={(event) => handleSortChange(parseSort(event.target.value))}
              />
            </label>
          </div>

          <p className="m-0 basis-full text-[11px] leading-[1.5] text-[#8fa0b8]">
            Prices are indicative and may vary by storage configuration, retailer, region and availability.
          </p>
        </section>
      )}

      {!loading && !error && highlightId !== null &&
        !filteredRecommendations.some((row) => row.recommendationId === highlightId) && (
          <div className="mb-[14px]">
            <Callout>
              {recommendations.some((row) => row.recommendationId === highlightId) ? (
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <span>The recommendation you opened is hidden by your current filters.</span>
                  <Button size="sm" variant="primary" onClick={() => setFilters(emptyRecommendationFilters())}>
                    Clear filters
                  </Button>
                </div>
              ) : (
                <span>
                  The recommendation you opened is no longer active. It may have been replaced by a
                  newer assessment, which is shown below if it is still relevant.
                </span>
              )}
            </Callout>
          </div>
        )}

      {!loading && !error && filteredRecommendations.length === 0 && filters.savedOnly && savedCount === 0 && (
        <EmptyState
          icon="☆"
          title="No saved recommendations yet"
          description="Select the star on a recommendation to save it here for quick access."
          action={
            <Button variant="primary" onClick={() => setFilters({ ...filters, savedOnly: false })}>
              Show all recommendations
            </Button>
          }
        />
      )}

      {!loading && !error && recommendations.length > 0 && filteredRecommendations.length === 0 && !(filters.savedOnly && savedCount === 0) && (
        <EmptyState
          icon="✦"
          title="No recommendations match your filters"
          description="Try another device, verdict or evidence grade, or clear your filters to see all recommendations."
          action={
            <Button variant="primary" onClick={() => setFilters(emptyRecommendationFilters())}>
              Clear filters
            </Button>
          }
        />
      )}

      {!loading &&
        !error &&
        filteredRecommendations.length > 0 && (
          <section
            aria-label="Device recommendations"
            className="grid gap-6"
          >
            {deviceGroups.map((group) => {
              const total = group.recommendations.length;
              const limit = TOP_RECOMMENDATIONS_PER_DEVICE;
              // A card opened from a notification is never hidden behind "Show all".
              const expanded =
                expandedDevices.has(group.deviceId) ||
                group.recommendations
                  .slice(limit)
                  .some((row) => row.recommendationId === highlightId);
              const shown = expanded
                ? group.recommendations
                : group.recommendations.slice(0, limit);

              return (
                <section
                  key={group.deviceId}
                  aria-label={`Recommendations for ${group.deviceName}`}
                >
                  <div className="mb-[10px] flex flex-wrap items-end justify-between gap-2">
                    <div className="min-w-0">
                      <p className="m-0 text-[11px] font-bold tracking-[0.12em] text-[#9fb1c9] uppercase">
                        For your device
                      </p>
                      <h3 className="mt-1 mb-0 text-[19px] font-bold text-[#eef5ff]">
                        {group.deviceName}
                      </h3>
                      <p className="mt-1 mb-0 text-[12px] text-[#8fa0b8]">
                        {total > limit && !expanded
                          ? `Top ${limit} of ${total} recommendations`
                          : `${total} recommendation${total === 1 ? "" : "s"}`}
                      </p>
                    </div>

                    {total > limit && (
                      <Button
                        size="sm"
                        variant="ghost"
                        aria-expanded={expanded}
                        onClick={() => toggleDeviceExpanded(group.deviceId)}
                      >
                        {expanded ? `Show top ${limit}` : `Show all ${total}`}
                      </Button>
                    )}
                  </div>

                  <div className="grid grid-cols-2 gap-4 max-[1000px]:grid-cols-1">
                    {shown.map((recommendation) => (
                      <RecommendationCard
                        key={recommendation.recommendationId}
                        recommendation={recommendation}
                        showDevice={false}
                        image={specs.candidateImage(recommendation.candidateProductId)}
                        highlighted={recommendation.recommendationId === highlightId}
                        onToggleStar={handleToggleStar}
                        onDismiss={openDismissal}
                        starPending={starPendingIds.has(
                          recommendation.recommendationId
                        )}
                      >
                        <SpecComparison
                          currentName={group.deviceName}
                          candidateName={candidateName(recommendation)}
                          loading={specs.loading}
                          current={specs.currentSpecs(recommendation.currentDeviceId)}
                          candidate={specs.candidateSpecs(recommendation.candidateProductId)}
                          changes={recommendation.specChanges}
                        />
                      </RecommendationCard>
                    ))}
                  </div>
                </section>
              );
            })}
          </section>
        )}

      {pendingUnstar && (
        <Modal
          eyebrow="Saved recommendations"
          title="Remove saved recommendation?"
          width="max-w-[460px]"
          onClose={() => setPendingUnstar(null)}
        >
          <p className="mt-0 mb-[14px] text-[13px] leading-[1.6] text-[#8fa0b8]">
            {candidateName(pendingUnstar)} for {pendingUnstar.currentDeviceName}{" "}
            will be removed from your saved recommendations. The recommendation
            itself stays on your dashboard.
          </p>

          <ModalActions>
            <Button variant="ghost" onClick={() => setPendingUnstar(null)}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmUnstar}>
              Remove
            </Button>
          </ModalActions>
        </Modal>
      )}

      {pendingDismissal && (
        <Modal
          eyebrow="Recommendation feedback"
          title="Dismiss this recommendation?"
          width="max-w-[520px]"
          onClose={closeDismissal}
        >
          <p className="mt-0 mb-4 text-[13px] leading-[1.6] text-[#8fa0b8]">
            Tell us why {candidateName(pendingDismissal)} is not suitable
            for {` ${pendingDismissal.currentDeviceName}`}. This product
            will be excluded temporarily while the feedback cooldown is
            active.
          </p>

          <form onSubmit={submitDismissal}>
            <Field
              label="Reason"
              htmlFor="dismissal-reason"
            >
              <Select
                id="dismissal-reason"
                value={dismissalReason}
                options={DISMISSAL_REASON_OPTIONS}
                onChange={(event) =>
                  setDismissalReason(
                    event.target
                      .value as RecommendationDismissalReason
                  )
                }
              />
            </Field>

            <Field
              label="Additional comment (optional)"
              htmlFor="dismissal-comment"
            >
              <Textarea
                id="dismissal-comment"
                value={dismissalComment}
                maxLength={1000}
                placeholder="Add any extra details that may improve future recommendations."
                onChange={(event) =>
                  setDismissalComment(event.target.value)
                }
              />

              <p className="m-0 text-right text-[11px] text-[#8fa0b8]">
                {dismissalComment.length}/1000
              </p>
            </Field>

            <FormError message={dismissalError} />

            <ModalActions>
              <Button
                type="button"
                variant="ghost"
                disabled={dismissalPending}
                onClick={closeDismissal}
              >
                Cancel
              </Button>

              <Button
                type="submit"
                variant="danger"
                loading={dismissalPending}
              >
                Dismiss recommendation
              </Button>
            </ModalActions>
          </form>
        </Modal>
      )}

      <ToastStack toasts={toasts} />
    </AppShell>
  );
}
