import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  DEFAULT_SORT,
  SORT_OPTIONS,
  listDashboardRecommendations,
  parseSort,
  setRecommendationStarred,
  type DashboardRecommendation,
  type SortOption,
} from "../api/dashboard";
import { signOut } from "../api/auth";
import { ApiError } from "../api/client";
import { getSession } from "../api/session";
import { AppShell } from "../components/layout/AppShell";
import type { NavKey } from "../components/layout/Sidebar";
import { NotificationButton } from "../components/layout/NotificationButton";
import { Button } from "../components/ui/Button";
import { Card } from "../components/ui/Card";
import { Callout } from "../components/ui/Callout";
import { Select } from "../components/ui/Form";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { Modal, ModalActions } from "../components/ui/Modal";
import { ToastStack } from "../components/ui/ToastStack";
import { useToasts } from "../components/ui/useToasts";
import { PageHeader } from "../components/ui/PageHeader";
import { EmptyState } from "../components/ui/EmptyState";
import { RecommendationFilterDropdown } from "../components/recommendations/RecommendationFilterDropdown";
import {
  emptyRecommendationFilters,
  filterRecommendations,
} from "../components/recommendations/recommendationOptions";
import {
  DASHBOARD_PATH,
  DEVICES_PATH,
  LOGIN_PATH,
} from "../routing/paths";
import { RecommendationCard } from "../components/recommendations/RecommendationCard";
import { verdictPresentation } from "../components/recommendations/recommendationPresentation";

export default function DashboardPage() {
  const navigate = useNavigate();
  const session = getSession();

  /*
   * Sort lives in the URL independently from the local filters, so changing
   * either control does not reset the other and refreshes keep the same order.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const sort = parseSort(searchParams.get("sort"));

  const [recommendations, setRecommendations] = useState<
    DashboardRecommendation[]
  >([]);

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState(emptyRecommendationFilters);
  const [pendingUnstar, setPendingUnstar] =
    useState<DashboardRecommendation | null>(null);
  const [starPendingIds, setStarPendingIds] = useState<ReadonlySet<number>>(
    () => new Set()
  );
  const { toasts, pushToast } = useToasts();
  // Bumped to refetch the current sort, e.g. after a row was replaced server-side.
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let cancelled = false;

    listDashboardRecommendations(sort)
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
  }, [navigate, sort, reloadCount]);

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
        <NotificationButton unread={attentionCount} />
      }
      onNavigate={handleNavigate}
      onSignOut={handleSignOut}
    >
      <PageHeader
        eyebrow="Upgrade overview"
        title="Your recommendations"
        description="See which registered devices require your attention and review their latest upgrade outcomes."
      />

      {!loading &&
        !error &&
        recommendations.length > 0 && (
          <section
            aria-label="Recommendation summary"
            className="mb-5 grid grid-cols-3 gap-4 max-[900px]:grid-cols-1"
          >
            <Card className="p-5">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase">
                Active recommendations
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#eef5ff]">
                {recommendations.length}
              </p>
            </Card>

            <Card className="p-5">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase">
                Devices requiring attention
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#f8d77d]">
                {attentionCount}
              </p>
            </Card>

            <Card className="p-5">
              <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase">
                No immediate upgrade
              </p>

              <p className="mt-2 mb-0 text-[30px] font-bold text-[#83e4c1]">
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
            className="grid grid-cols-2 gap-4 max-[1000px]:grid-cols-1"
          >
            {filteredRecommendations.map((recommendation) => (
              <RecommendationCard
                key={recommendation.recommendationId}
                recommendation={recommendation}
                onToggleStar={handleToggleStar}
                starPending={starPendingIds.has(
                  recommendation.recommendationId
                )}
              />
            ))}
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

      <ToastStack toasts={toasts} />
    </AppShell>
  );
}
