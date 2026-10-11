import { Range } from "../ui/Form";
import { money } from "./deviceOptions";

/** Replacement-budget slider (200–5,000) with a live value badge. */
export function BudgetSlider({
  value,
  currency,
  onChange,
}: {
  value: number;
  /** ISO 4217 code shown on the badge; unset means SGD. */
  currency?: string | null;
  onChange: (value: number) => void;
}) {
  return (
    <div className="grid grid-cols-[1fr_auto] items-center gap-3">
      <Range
        min={200}
        max={5000}
        step={100}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label="Maximum budget"
      />
      <div className="min-w-[90px] rounded-[10px] border border-white/[0.09] bg-[#0b1526] px-[10px] py-2 text-center text-[12px] font-extrabold">
        {money(value, currency)}
      </div>
    </div>
  );
}
