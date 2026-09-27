import type { ReactNode } from "react";

/** Small purple pill (daisyUI `badge`), e.g. a priority on a device card. */
export function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="badge h-auto rounded-full border-[#7c5cff]/20 bg-[#7c5cff]/10 px-[7px] py-[5px] text-[10px] text-[#cfc7ff]">
      {children}
    </span>
  );
}
