import type { ReactNode } from "react";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { DeviceThumb } from "../devices/DeviceThumb";
import {
  evidenceGradeText,
  formatRecommendationPrice,
  priceConversionNote,
  recommendationCardId,
  verdictPresentation,
} from "./recommendationPresentation";
import type { DashboardRecommendation } from "./types";

export function RecommendationCard({
  recommendation,
  onToggleStar,
  onDismiss,
  starPending = false,
  highlighted = false,
  showDevice = true,
  image,
  children,
}: {
  recommendation: DashboardRecommendation;
  /** Shows the star toggle. The caller decides whether unstarring needs confirming. */
  onToggleStar?: (recommendation: DashboardRecommendation) => void;
  /** Opens the feedback form before dismissing this recommendation. */
  onDismiss?: (recommendation: DashboardRecommendation) => void;
  /** Disables the star while its save/remove request runs. */
  starPending?: boolean;
  /** Outlines the card, e.g. after opening it from a notification. */
  highlighted?: boolean;
  /**
   * Heads the card with the owned device. Turn off when the page already
   * groups cards under a device heading; the suggested product is then the title.
   */
  showDevice?: boolean;
  /** Suggested product's thumbnail beside its name when `showDevice` is off; the phone emoji without one. */
  image?: string;
  /** Extra panels (e.g. a spec comparison) shown under the price. */
  children?: ReactNode;
}) {
  const verdict = verdictPresentation(
    recommendation.verdict
  );
  const conversionNote = priceConversionNote(
    recommendation.priceConversionStatus ?? "ORIGINAL",
    recommendation.exchangeRateDate ?? null
  );

  const candidateName = [
    recommendation.candidateBrand,
    recommendation.candidateModelName,
  ]
    .filter(Boolean)
    .join(" ");

  const price = formatRecommendationPrice(
    recommendation.latestPrice,
    recommendation.currency
  );

  return (
    <Card
      id={recommendationCardId(recommendation.recommendationId)}
      className={`h-full overflow-hidden ${highlighted ? "ring-2 ring-[#7c5cff]" : ""}`}
      aria-label={`Recommendation for ${recommendation.currentDeviceName}`}
      aria-current={highlighted ? "true" : undefined}
    >
      <div className="card-body gap-0 p-[18px]">
        <div className="flex flex-wrap items-start justify-between gap-3">
          {!showDevice && (
            <DeviceThumb
              size="sm"
              device={{
                type: "Phone",
                brand: recommendation.candidateBrand,
                model: recommendation.candidateModelName,
                image,
              }}
            />
          )}
          <div className="min-w-0 flex-1">
            <p className="m-0 text-[11px] font-bold tracking-[0.12em] text-[#9fb1c9] uppercase">
              {showDevice ? "For your device" : "Suggested product"}
            </p>

            {showDevice ? (
              <h3 className="mt-1 mb-0 text-[19px] font-bold text-[#eef5ff]">
                {recommendation.currentDeviceName}
              </h3>
            ) : (
              <h4 className="mt-1 mb-0 text-[17px] font-extrabold text-[#eef5ff]">
                {candidateName}
              </h4>
            )}
          </div>

          <div className="flex items-center gap-2">
            <span
              className={`badge rounded-full px-3 py-[14px] text-[11px] font-semibold ${verdict.badgeClass}`}
            >
              {verdict.label}
            </span>

            {onToggleStar && (
              <Button
                variant="ghost"
                size="sm"
                aria-pressed={recommendation.starred}
                aria-label={`${recommendation.starred ? "Remove" : "Save"} ${candidateName} for ${recommendation.currentDeviceName}`}
                title={recommendation.starred ? "Remove from saved" : "Save recommendation"}
                loading={starPending}
                className={`text-[16px] leading-none ${recommendation.starred ? "text-[#f6b84b]" : ""}`}
                onClick={() => onToggleStar(recommendation)}
              >
                {!starPending && (
                  <span aria-hidden="true">{recommendation.starred ? "★" : "☆"}</span>
                )}
              </Button>
            )}
          </div>
        </div>

        {showDevice ? (
          <div className="mt-4 rounded-[14px] border border-white/[0.09] bg-[#0a1423] p-[16px]">
            <p className="m-0 text-[11px] font-bold tracking-[0.12em] text-[#9fb1c9] uppercase">
              Suggested product
            </p>

            <p className="mt-1 mb-0 text-[17px] font-extrabold text-[#eef5ff]">
              {candidateName}
            </p>

            <p className="mt-3 mb-0 text-[11px] font-bold tracking-[0.12em] text-[#9fb1c9] uppercase">
              Indicative price
            </p>

            <p className="mt-1 mb-0 text-[18px] font-bold text-[#a998ff]">
              {price}
            </p>
          </div>
        ) : (
          <p className="mt-3 mb-0 flex grow-0 flex-wrap items-baseline gap-x-2 text-[12px] text-[#8fa0b8]">
            Indicative price
            <span className="text-[18px] font-bold text-[#a998ff]">{price}</span>
          </p>
        )}

        {conversionNote && (
          <p className="mt-1 mb-0 text-[11px] leading-[1.45] text-[#f6b84b]">
            {conversionNote}
          </p>
        )}

        {children}

        <div className="mt-4 mb-4">
          <p className="m-0 text-[12px] font-semibold text-[#c6d1df]">
            Why this recommendation
          </p>

          <p className="mt-1 mb-0 text-[14px] leading-[1.6] text-[#9eacc0]">
            {recommendation.reasoning ??
              "No explanation is available for this recommendation."}
          </p>
        </div>

        {/* Pinned to the bottom so cards in a row line up whatever their text length. */}
        <div className="mt-auto flex flex-wrap items-center justify-between gap-3 border-t border-white/[0.09] pt-4">
          <span className="inline-flex rounded-full border border-white/[0.09] bg-white/[0.04] px-3 py-[7px] text-[12px] font-semibold text-[#bdc7d7]">
            {evidenceGradeText(
              recommendation.confidence
            )}
          </span>

          {onDismiss && (
            <Button
              variant="ghost"
              size="sm"
              className="text-[#ff9ca5] hover:bg-[#ff6d7a]/10 hover:text-[#ffb6bd]"
              onClick={() => onDismiss(recommendation)}
            >
              Not interested
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
