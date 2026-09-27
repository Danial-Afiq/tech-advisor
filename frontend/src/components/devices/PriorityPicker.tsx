import { PRIORITIES } from "./deviceOptions";

/** Multi-select pill toggles for upgrade priorities. */
export function PriorityPicker({
  selected,
  onChange,
  options = PRIORITIES,
}: {
  selected: string[];
  onChange: (selected: string[]) => void;
  options?: string[];
}) {
  const toggle = (option: string) =>
    onChange(
      selected.includes(option)
        ? selected.filter((x) => x !== option)
        : [...selected, option]
    );

  return (
    <div className="flex flex-wrap gap-2">
      {options.map((option) => {
        const active = selected.includes(option);
        return (
          <button
            key={option}
            type="button"
            aria-pressed={active}
            onClick={() => toggle(option)}
            className={`btn btn-sm h-auto min-h-0 rounded-full px-[11px] py-[9px] text-[12px] font-bold shadow-none ${
              active
                ? "border-[#7c5cff]/40 bg-[#7c5cff]/14 text-[#f0ecff]"
                : "border-white/[0.09] bg-[#0b1526] text-[#9eb0c6]"
            }`}
          >
            {option}
          </button>
        );
      })}
    </div>
  );
}
