import type { Condition } from "./types";

const TONE: Record<Condition, string> = {
  Excellent: "badge-success",
  Good: "badge-success",
  Fair: "badge-warning",
  Poor: "badge-error",
};

/** daisyUI soft `badge` coloured by device condition. */
export function ConditionBadge({ condition }: { condition: Condition }) {
  return (
    <span
      className={`badge badge-soft badge-sm h-auto self-start px-2 py-[5px] text-[11px] font-bold ${TONE[condition]}`}
    >
      {condition}
    </span>
  );
}
