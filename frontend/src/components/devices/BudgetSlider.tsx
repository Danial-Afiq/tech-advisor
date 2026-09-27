import { Range } from "../ui/Form";
import { money } from "./deviceOptions";

/** Replacement-budget slider (S$200–5,000) with a live value badge. */
export function BudgetSlider({
  value,
  onChange,
}: {
  value: number;
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
        {money(value)}
      </div>
    </div>
  );
}
