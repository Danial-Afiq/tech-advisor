/** Labelled percentage bar: daisyUI `progress` with a purple→cyan fill. */
export function Meter({ label, value }: { label: string; value: number }) {
  return (
    <div className="grid grid-cols-[76px_1fr_40px] items-center gap-[10px] text-[11px] text-[#a9b7c8]">
      <span>{label}</span>
      <progress
        className="progress h-[7px] border border-white/[0.04] bg-[#091322] text-[#7c5cff] [&::-moz-progress-bar]:bg-[linear-gradient(90deg,#7c5cff,#28c0ff)] [&::-webkit-progress-value]:bg-[linear-gradient(90deg,#7c5cff,#28c0ff)]"
        value={value}
        max={100}
      />
      <b>{value}%</b>
    </div>
  );
}
