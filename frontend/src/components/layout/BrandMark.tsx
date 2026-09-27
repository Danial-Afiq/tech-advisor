/** Gradient square with the sparkle logo, optionally followed by the name. */
export function BrandMark({ showName = true }: { showName?: boolean }) {
  return (
    <div className="flex items-center gap-[10px] font-extrabold tracking-[-0.02em]">
      <div className="grid h-[34px] w-[34px] shrink-0 place-items-center rounded-[11px] bg-[linear-gradient(135deg,#7c5cff,#28c0ff)] shadow-[0_12px_30px_rgba(124,92,255,.25)]">
        <svg viewBox="0 0 24 24" fill="none" className="h-[19px] w-[19px]">
          <path
            d="M12 2l1.6 5.1L19 9l-5.4 1.8L12 16l-1.6-5.2L5 9l5.4-1.9L12 2Z"
            fill="white"
          />
          <path
            d="M18.5 14l.9 2.6L22 17.5l-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9.9-2.6Z"
            fill="white"
            opacity=".7"
          />
        </svg>
      </div>
      {showName && <span className="max-[860px]:hidden">Tech Advisor</span>}
    </div>
  );
}
