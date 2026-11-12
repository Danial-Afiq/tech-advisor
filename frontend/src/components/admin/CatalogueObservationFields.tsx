import type { ReactNode } from "react";
import { SUPPORTED_CURRENCIES } from "../currency/currency";
import { Button } from "../ui/Button";
import { Eyebrow } from "../ui/Eyebrow";
import { Field, FieldHint, Input, Select } from "../ui/Form";
import { emptyBenchmarkRow, emptyPriceRow } from "./catalogueAdmin";
import type { BenchmarkRow, PriceRow } from "./catalogueAdmin";

/** Three columns that collapse to one on phones. */
function RowGrid({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-3 gap-x-3 max-[620px]:grid-cols-1">{children}</div>
  );
}

function Row({
  legend,
  onRemove,
  children,
}: {
  legend: string;
  onRemove: () => void;
  children: ReactNode;
}) {
  return (
    <div role="group" aria-label={legend} className="mb-3 rounded-[12px] border border-white/[0.09] px-3 pt-2">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[12px] font-semibold text-[#8fa0b8]">{legend}</span>
        <Button size="sm" variant="ghost" aria-label={`Remove ${legend.toLowerCase()}`} onClick={onRemove}>
          Remove
        </Button>
      </div>
      {children}
    </div>
  );
}

const replaceAt = <T,>(list: T[], index: number, patch: Partial<T>) =>
  list.map((row, i) => (i === index ? { ...row, ...patch } : row));

/**
 * Price (and, for smartphones, benchmark) rows recorded with a new catalogue
 * entry. Untouched rows are ignored, so the prefilled empty row is optional.
 * The newest price becomes the one budgets and recommendations use.
 */
export function CatalogueObservationFields({
  prices,
  benchmarks,
  priceVariants,
  benchmarkEntry,
  onChange,
}: {
  prices: PriceRow[];
  benchmarks: BenchmarkRow[];
  /** Offer storage/RAM on price rows (smartphones). */
  priceVariants: boolean;
  /** Offer benchmark rows (smartphones). */
  benchmarkEntry: boolean;
  onChange: (patch: { prices?: PriceRow[]; benchmarks?: BenchmarkRow[] }) => void;
}) {
  const setPrice = (i: number, patch: Partial<PriceRow>) =>
    onChange({ prices: replaceAt(prices, i, patch) });
  const setBenchmark = (i: number, patch: Partial<BenchmarkRow>) =>
    onChange({ benchmarks: replaceAt(benchmarks, i, patch) });

  return (
    <section
      aria-label={benchmarkEntry ? "Initial prices and benchmarks" : "Initial prices"}
      className="mb-[14px] border-t border-white/[0.09] pt-4"
    >
      <h4 className="m-0 text-[16px] font-extrabold text-[#eef5ff]">
        {benchmarkEntry ? "Pricing & benchmarks" : "Pricing"}
      </h4>
      <div className="mb-3 mt-1">
        <FieldHint>
          Optional; leave a row empty to skip it. The newest price is the one used for
          budgets and recommendations. A blank source is saved as "admin".
        </FieldHint>
      </div>

      <div className="mb-2">
        <Eyebrow>Price</Eyebrow>
      </div>
      {prices.map((row, i) => (
        <Row
          key={i}
          legend={`Price ${i + 1}`}
          onRemove={() => onChange({ prices: prices.filter((_, j) => j !== i) })}
        >
          <RowGrid>
            <Field label="Observed date">
              <Input
                type="date"
                value={row.observedOn}
                onChange={(e) => setPrice(i, { observedOn: e.target.value })}
              />
            </Field>
            <Field label="Price">
              <Input
                type="number"
                inputMode="decimal"
                min={0}
                step="0.01"
                value={row.price}
                onChange={(e) => setPrice(i, { price: e.target.value })}
              />
            </Field>
            <Field label="Currency">
              <Select
                value={row.currency}
                options={SUPPORTED_CURRENCIES}
                onChange={(e) => setPrice(i, { currency: e.target.value })}
              />
            </Field>
            {priceVariants && (
              <>
                <Field label="Variant storage (GB)">
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={1}
                    step={1}
                    placeholder="Any"
                    value={row.storageGb}
                    onChange={(e) => setPrice(i, { storageGb: e.target.value })}
                  />
                </Field>
                <Field label="Variant RAM (GB)">
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={1}
                    step={1}
                    placeholder="Any"
                    value={row.ramGb}
                    onChange={(e) => setPrice(i, { ramGb: e.target.value })}
                  />
                </Field>
              </>
            )}
            <Field label="Source">
              <Input
                maxLength={200}
                placeholder="admin"
                value={row.source}
                onChange={(e) => setPrice(i, { source: e.target.value })}
              />
            </Field>
          </RowGrid>
        </Row>
      ))}
      {priceVariants && (
        <div className="mb-2">
          <FieldHint>Leave the variant blank for a price that applies to the whole model.</FieldHint>
        </div>
      )}
      <Button
        size="sm"
        variant="ghost"
        className="mb-3"
        onClick={() => onChange({ prices: [...prices, emptyPriceRow()] })}
      >
        + Add price
      </Button>

      {benchmarkEntry && (
        <>
          <div className="mb-2 mt-2">
            <Eyebrow>Benchmarks</Eyebrow>
          </div>
          {benchmarks.map((row, i) => (
            <Row
              key={i}
              legend={`Benchmark ${i + 1}`}
              onRemove={() => onChange({ benchmarks: benchmarks.filter((_, j) => j !== i) })}
            >
              <RowGrid>
                <Field label="Benchmark name">
                  <Input
                    maxLength={100}
                    placeholder="e.g. Geekbench 6 CPU Multi-Core"
                    value={row.benchmarkName}
                    onChange={(e) => setBenchmark(i, { benchmarkName: e.target.value })}
                  />
                </Field>
                <Field label="Score">
                  <Input
                    type="number"
                    inputMode="decimal"
                    min={0}
                    step="any"
                    value={row.score}
                    onChange={(e) => setBenchmark(i, { score: e.target.value })}
                  />
                </Field>
                <Field label="Observed date">
                  <Input
                    type="date"
                    value={row.observedOn}
                    onChange={(e) => setBenchmark(i, { observedOn: e.target.value })}
                  />
                </Field>
                <Field label="Source">
                  <Input
                    maxLength={200}
                    placeholder="admin"
                    value={row.source}
                    onChange={(e) => setBenchmark(i, { source: e.target.value })}
                  />
                </Field>
              </RowGrid>
            </Row>
          ))}
          <div className="mb-2">
            <FieldHint>
              Use the exact name of an existing benchmark so results can be compared; its unit
              and whether higher is better are copied from the stored results.
            </FieldHint>
          </div>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => onChange({ benchmarks: [...benchmarks, emptyBenchmarkRow()] })}
          >
            + Add benchmark
          </Button>
        </>
      )}
    </section>
  );
}
