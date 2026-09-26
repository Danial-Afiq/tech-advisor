import type { ReactNode } from "react";

/** Sticky blurred bar above page content; `actions` render on the right. */
export function Topbar({
  title,
  actions,
}: {
  title: string;
  actions?: ReactNode;
}) {
  return (
    <header className="sticky top-0 z-[9] flex items-center justify-between gap-[14px] border-b border-white/[0.09] bg-[#08101f]/70 px-[28px] py-[18px] backdrop-blur-[18px] max-[860px]:px-4 max-[860px]:py-[14px]">
      <div className="text-[17px] font-extrabold max-[620px]:hidden">
        {title}
      </div>
      <div className="ml-auto flex items-center gap-[9px]">{actions}</div>
    </header>
  );
}
