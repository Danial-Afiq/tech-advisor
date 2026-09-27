/** Centred daisyUI spinner for a section that is still loading. */
export function LoadingBlock({ label = "Loading…" }: { label?: string }) {
  return (
    <div
      role="status"
      className="grid place-items-center gap-3 py-[50px] text-[13px] text-[#8fa0b8]"
    >
      <span className="loading loading-spinner loading-md text-[#7c5cff]" />
      {label}
    </div>
  );
}
