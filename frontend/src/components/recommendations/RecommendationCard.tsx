import { Card } from "../ui/Card";
import {
  evidenceGradeText,
  formatRecommendationPrice,
  verdictPresentation,
} from "./recommendationPresentation";
import type { DashboardRecommendation } from "./types";

export function RecommendationCard({
  recommendation,
}: {
  recommendation: DashboardRecommendation;
}) {
  const verdict = verdictPresentation(
    recommendation.verdict
  );

  const candidateName = [
    recommendation.candidateBrand,
    recommendation.candidateModelName,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <Card
      className="h-full overflow-hidden"
      aria-label={`Recommendation for ${recommendation.currentDeviceName}`}
    >
      <div className="card-body gap-0 p-[18px]">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="m-0 text-[11px] font-bold tracking-[0.12em] text-[#9fb1c9] uppercase">
              For your device
            </p>

            <h3 className="mt-1 mb-0 text-[19px] font-bold text-[#eef5ff]">
              {recommendation.currentDeviceName}
            </h3>
          </div>

          <span
            className={`badge rounded-full px-3 py-[14px] text-[11px] font-semibold ${verdict.badgeClass}`}
          >
            {verdict.label}
          </span>
        </div>

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
            {formatRecommendationPrice(
              recommendation.latestPrice,
              recommendation.currency
            )}
          </p>
        </div>

        <div className="mt-4">
          <p className="m-0 text-[12px] font-semibold text-[#c6d1df]">
            Why this recommendation
          </p>

          <p className="mt-1 mb-0 text-[14px] leading-[1.6] text-[#9eacc0]">
            {recommendation.reasoning ??
              "No explanation is available for this recommendation."}
          </p>
        </div>

        <div className="mt-4 border-t border-white/[0.09] pt-4">
          <span className="inline-flex rounded-full border border-white/[0.09] bg-white/[0.04] px-3 py-[7px] text-[12px] font-semibold text-[#bdc7d7]">
            {evidenceGradeText(
              recommendation.confidence
            )}
          </span>
        </div>
      </div>
    </Card>
  );
}
