import type { ReactNode } from "react";

/** Small uppercase label shown above headings. */
export function Eyebrow({ children }: { children: ReactNode }) {
  return (
    <div className="text-[12px] font-bold uppercase tracking-[.12em] text-[#9fb1c9]">
      {children}
    </div>
  );
}
