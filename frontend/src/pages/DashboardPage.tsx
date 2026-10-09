import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  listDashboardRecommendations,
  type DashboardRecommendation,
} from "../api/dashboard";
import { ApiError } from "../api/client";
import { getSession } from "../api/session";
import { AppShell } from "../components/layout/AppShell";
import type { NavKey } from "../components/layout/Sidebar";
import { NotificationButton } from "../components/layout/NotificationButton";
import { Button } from "../components/ui/Button";
import { Card } from "../components/ui/Card";
import { Callout } from "../components/ui/Callout";
import { LoadingBlock } from "../components/ui/LoadingBlock";
import { PageHeader } from "../components/ui/PageHeader";
import {
  DASHBOARD_PATH,
  DEVICES_PATH,
  LOGIN_PATH,
} from "../routing/paths";

function verdictDetails(verdict: string) {
  switch (verdict) {
    case "RECOMMENDED":
      return {
        label: "Upgrade recommended",
        colour:
          "border-[#ff8a65]/35 bg-[#ff8a65]/10 text-[#ffb199]",
        requiresAttention: true,
      };

    case "WORTH_CONSIDERING":
      return {
        label: "Worth considering",
        colour:
          "border-[#f5c451]/35 bg-[#f5c451]/10 text-[#f8d77d]",
        requiresAttention: true,
      };

    case "NOT_RECOMMENDED":
      return {
        label: "No upgrade needed",
        colour:
          "border-[#58d6a9]/35 bg-[#58d6a9]/10 text-[#83e4c1]",
        requiresAttention: false,
      };

    default:
      return {
        label: verdict.replaceAll("_", " "),
        colour:
          "border-white/15 bg-white/[0.06] text-[#bdc7d7]",
        requiresAttention: false,
      };
  }
}

function formatPrice(
  price: number | null,
  currency: string | null
) {
  if (price === null) {
    return "Price unavailable";
  }

  return new Intl.NumberFormat("en-SG", {
    style: "currency",
    currency: currency ?? "SGD",
  }).format(price);
}

export default function DashboardPage() {
  const navigate = useNavigate();
  const session = getSession();

  const [recommendations, setRecommendations] = useState<
    DashboardRecommendation[]
  >([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    listDashboardRecommendations()
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
        }
      });

    return () => {
      cancelled = true;
    };
  }, [navigate]);

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
      verdictDetails(recommendation.verdict).requiresAttention;

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

  const handleNavigate = (key: NavKey) => {
    if (key === "dashboard") {
      void navigate(DASHBOARD_PATH);
    }

    if (key === "devices") {
      void navigate(DEVICES_PATH);
    }
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
          <Card className="grid place-items-center gap-3 p-10 text-center">
            <div className="text-[30px]">✦</div>

            <div>
              <h2 className="m-0 text-[20px]! text-[#eef5ff]!">
                No active recommendations yet
              </h2>

              <p className="mt-2 mb-0 max-w-[520px] text-[14px] leading-[1.6] text-[#8fa0b8]">
                Recommendations will appear here when an upgrade
                outcome is available for one of your registered
                devices.
              </p>
            </div>

            <Button
              variant="primary"
              onClick={() => navigate(DEVICES_PATH)}
            >
              View my devices
            </Button>
          </Card>
        )}

      {!loading &&
        !error &&
        recommendations.length > 0 && (
          <section
            aria-label="Device recommendations"
            className="grid grid-cols-2 gap-4 max-[1000px]:grid-cols-1"
          >
            {recommendations.map((recommendation) => {
              const verdict = verdictDetails(
                recommendation.verdict
              );

              return (
                <Card
                  key={recommendation.recommendationId}
                  className="p-5"
                >
                  <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <p className="m-0 text-[12px] font-semibold tracking-[0.08em] text-[#8fa0b8] uppercase">
                        Current device
                      </p>

                      <h2 className="mt-1 mb-0 text-[20px] font-bold text-[#eef5ff]">
                        {recommendation.currentDeviceName}
                      </h2>
                    </div>

                    <span
                      className={`rounded-full border px-3 py-1 text-[12px] font-semibold ${verdict.colour}`}
                    >
                      {verdict.label}
                    </span>
                  </div>

                  <div className="rounded-[14px] border border-white/[0.08] bg-white/[0.035] p-4">
                    <p className="m-0 text-[12px] text-[#8fa0b8]">
                      Suggested product
                    </p>

                    <p className="mt-1 mb-0 text-[17px] font-semibold text-[#eef5ff]">
                      {recommendation.candidateBrand}{" "}
                      {recommendation.candidateModelName}
                    </p>

                    <p className="mt-2 mb-0 text-[19px] font-bold text-[#a998ff]">
                      {formatPrice(
                        recommendation.latestPrice,
                        recommendation.currency
                      )}
                    </p>
                  </div>

                  <div className="mt-4 flex items-center gap-2 text-[13px] text-[#bdc7d7]">
                    <span>Evidence confidence:</span>

                    <b className="text-[#eef5ff]">
                      {recommendation.confidence ??
                        "Not available"}
                    </b>
                  </div>

                  {recommendation.reasoning && (
                    <p className="mt-3 mb-0 text-[14px] leading-[1.6] text-[#9eacc0]">
                      {recommendation.reasoning}
                    </p>
                  )}
                </Card>
              );
            })}
          </section>
        )}
    </AppShell>
  );
}