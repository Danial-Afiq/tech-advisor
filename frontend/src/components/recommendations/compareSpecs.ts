import { PHONE_SPEC_GROUPS, formatSpec } from "../devices/phoneSpecs";
import type { PhoneSpecs } from "../devices/phoneSpecs";
import type { SpecChange } from "./types";

export type SpecComparisonRow = {
  label: string;
  current: string | null;
  candidate: string | null;
  /** Both sides are known and read differently. */
  differs: boolean;
  /** The verdict's judgement (backend `specChanges`); absent for text or unscored specs. */
  better?: boolean;
  /** The suggested value is higher than yours; absent unless both are numbers. */
  rising?: boolean;
};

const camel = (spec: string) => spec.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
const FIELDS = PHONE_SPEC_GROUPS.flatMap((group) => group.fields);

/** Rows to compare, grouped like `PhoneSpecsDropdown`; specs unknown on both sides are left out. */
export function compareSpecs(current?: PhoneSpecs, candidate?: PhoneSpecs, changes: SpecChange[] = []) {
  const deltas = new Map(changes.map((change) => [camel(change.spec), change.deltaPct]));
  return PHONE_SPEC_GROUPS.map((group) => ({
    title: group.title,
    rows: group.fields
      .map((field): SpecComparisonRow => {
        const from = current?.[field.key];
        const to = candidate?.[field.key];
        const a = formatSpec(field, from);
        const b = formatSpec(field, to);
        const delta = deltas.get(field.key);
        return {
          label: field.label,
          current: a,
          candidate: b,
          differs: a !== null && b !== null && a !== b,
          better: delta === undefined || delta === 0 ? undefined : delta > 0,
          rising: typeof from === "number" && typeof to === "number" && from !== to ? to > from : undefined,
        };
      })
      .filter((row) => row.current !== null || row.candidate !== null),
  })).filter((group) => group.rows.length > 0);
}

/**
 * The biggest spec changes as short phrases, e.g. "Battery 54% better".
 * Changes under 1% are noise. (The backend already leaves out price and text specs.)
 */
export function specChangeSummary(changes: SpecChange[] = [], limit = 3) {
  return changes
    .filter((change) => Math.abs(change.deltaPct) >= 1)
    .slice(0, limit)
    .map((change) => {
      const label =
        change.spec === "benchmark_score"
          ? "Benchmark"
          : (FIELDS.find((field) => field.key === camel(change.spec))?.label ?? change.spec);
      return `${label} ${Math.round(Math.abs(change.deltaPct))}% ${change.deltaPct > 0 ? "better" : "worse"}`;
    });
}
