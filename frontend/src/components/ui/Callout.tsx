import type { ReactNode } from "react";

/** Dashed purple note box (the prototype's "demo-note"). */
export function Callout({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`rounded-[12px] border border-dashed border-[#7c5cff]/35 bg-[#7c5cff]/[0.07] p-3 text-[12px] leading-[1.55] text-[#bdc7d7] ${className}`}
    >
      {children}
    </div>
  );
}
