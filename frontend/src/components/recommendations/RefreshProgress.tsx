import { useEffect, useState } from "react";
import { REFRESH_WINDOW_MS } from "./recommendationRefresh";

function percentDone(until: number): number {
  const elapsed = REFRESH_WINDOW_MS - (until - Date.now());
  return Math.min(100, Math.max(0, Math.round((elapsed / REFRESH_WINDOW_MS) * 100)));
}

/**
 * Time-based loading bar shown while the server regenerates recommendations
 * after a device change. It fills over the refresh window; the server gives
 * no real progress signal, so it is an estimate, not a completion indicator.
 */
export function RefreshProgress({ until }: Readonly<{ until: number }>) {
  const [percent, setPercent] = useState(() => percentDone(until));

  useEffect(() => {
    const tick = window.setInterval(() => setPercent(percentDone(until)), 1000);
    return () => window.clearInterval(tick);
  }, [until]);

  return (
    <div className="mb-[14px]">
      <p className="m-0 mb-[6px] text-[13px] text-[#8fa0b8]">
        Updating recommendations after your recent device change. New results
        appear here automatically.
      </p>
      <div
        role="progressbar"
        aria-label="Updating recommendations"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="h-[6px] w-full overflow-hidden rounded-full bg-[#1f2a3d]"
      >
        <div
          className="h-full rounded-full bg-[#4f8cff] transition-[width] duration-1000 ease-linear"
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}
