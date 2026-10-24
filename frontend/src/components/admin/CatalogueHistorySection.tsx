import { useEffect, useState } from "react";
import { listSmartphoneBenchmarks, listSmartphonePrices } from "../../api/catalogue";
import type { CatalogueBenchmark, CataloguePrice } from "../../api/catalogue";
import { formatDate } from "../devices/deviceOptions";
import { formatRecommendationPrice } from "../recommendations/recommendationPresentation";
import { Button } from "../ui/Button";
import { Callout } from "../ui/Callout";
import { Eyebrow } from "../ui/Eyebrow";
import { FieldHint } from "../ui/Form";

/** Rows shown before "Show all". */
const PREVIEW_ROWS = 5;

type History = {
  prices: CataloguePrice[];
  benchmarks: CatalogueBenchmark[];
};

const head = "border-b border-white/[0.09] px-2 py-2 text-left font-semibold";
const cell = "border-b border-white/[0.06] px-2 py-2 align-top";

const muted = (text: string | null) => text ?? <span className="text-[#5d6e86]">—</span>;

/**
 * Read-only price and benchmark history of one catalogue entry, newest first,
 * shown in the Edit modal. The newest price is the one recommendations use.
 */
export function CatalogueHistorySection({ productId }: { productId: number }) {
  const [history, setHistory] = useState<History | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showAllPrices, setShowAllPrices] = useState(false);
  const [showAllBenchmarks, setShowAllBenchmarks] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listSmartphonePrices(productId), listSmartphoneBenchmarks(productId)])
      .then(([prices, benchmarks]) => {
        if (!cancelled) setHistory({ prices, benchmarks });
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Could not load the history.");
      });
    return () => {
      cancelled = true;
    };
  }, [productId]);

  const prices = history?.prices ?? [];
  const benchmarks = history?.benchmarks ?? [];
  const current = prices[0];

  return (
    <section
      aria-label="Pricing and benchmarks"
      className="mb-[14px] border-t border-white/[0.09] pt-4"
    >
      <h4 className="m-0 text-[16px] font-extrabold text-[#eef5ff]">Pricing & benchmarks</h4>
      <div className="mb-3 mt-1">
        <FieldHint>
          Read-only. Every observation is kept; the newest price is the one used for
          budgets and recommendations.
        </FieldHint>
      </div>

      {error && <Callout>{error}</Callout>}
      {!history && !error && (
        <p role="status" className="m-0 text-[12px] text-[#8fa0b8]">
          Loading history…
        </p>
      )}

      {history && (
        <>
          <div className="mb-2">
            <Eyebrow>Price history</Eyebrow>
          </div>
          {current ? (
            <>
              <p className="m-0 mb-2 text-[13px] text-[#c6d1df]">
                Current price{" "}
                <strong className="text-[18px] text-[#eef5ff]">
                  {formatRecommendationPrice(Number(current.price), current.currency)}
                </strong>{" "}
                <span className="text-[12px] text-[#8fa0b8]">
                  as of {formatDate(current.observedAt)}
                </span>
              </p>
              <div className="overflow-x-auto">
                <table className="w-full text-[12px]">
                  <thead className="text-[#8fa0b8]">
                    <tr>
                      <th className={head}>Observed</th>
                      <th className={head}>Price</th>
                      <th className={head}>Variant</th>
                      <th className={head}>Source</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(showAllPrices ? prices : prices.slice(0, PREVIEW_ROWS)).map((p) => (
                      <tr key={p.id}>
                        <td className={cell}>{formatDate(p.observedAt)}</td>
                        <td className={`${cell} whitespace-nowrap`}>
                          {formatRecommendationPrice(Number(p.price), p.currency)}
                        </td>
                        <td className={cell}>{p.variant ?? "Model-level"}</td>
                        <td className={cell}>{muted(p.source)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {prices.length > PREVIEW_ROWS && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="mt-2"
                  onClick={() => setShowAllPrices((all) => !all)}
                >
                  {showAllPrices ? "Show fewer" : `Show all ${prices.length} prices`}
                </Button>
              )}
            </>
          ) : (
            <FieldHint>
              No price yet. Without one, this phone is never recommended.
            </FieldHint>
          )}

          <div className="mb-2 mt-4">
            <Eyebrow>Benchmarks</Eyebrow>
          </div>
          {benchmarks.length > 0 ? (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-[12px]">
                  <thead className="text-[#8fa0b8]">
                    <tr>
                      <th className={head}>Benchmark</th>
                      <th className={head}>Score</th>
                      <th className={head}>Observed</th>
                      <th className={head}>Source</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(showAllBenchmarks ? benchmarks : benchmarks.slice(0, PREVIEW_ROWS)).map(
                      (b) => (
                        <tr key={b.id}>
                          <td className={cell}>
                            {b.benchmarkName}
                            <small className="block text-[11px] text-[#8fa0b8]">
                              {b.higherIsBetter ? "Higher is better" : "Lower is better"}
                            </small>
                          </td>
                          <td className={`${cell} whitespace-nowrap`}>
                            {Number(b.score).toLocaleString()}
                            {b.unit ? ` ${b.unit}` : ""}
                          </td>
                          <td className={cell}>{formatDate(b.observedAt)}</td>
                          <td className={cell}>{muted(b.source)}</td>
                        </tr>
                      )
                    )}
                  </tbody>
                </table>
              </div>
              {benchmarks.length > PREVIEW_ROWS && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="mt-2"
                  onClick={() => setShowAllBenchmarks((all) => !all)}
                >
                  {showAllBenchmarks ? "Show fewer" : `Show all ${benchmarks.length} benchmarks`}
                </Button>
              )}
            </>
          ) : (
            <FieldHint>No benchmark results yet.</FieldHint>
          )}
        </>
      )}
    </section>
  );
}
