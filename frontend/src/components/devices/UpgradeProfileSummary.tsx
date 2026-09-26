import { Tag } from "../ui/Tag";
import { money } from "./deviceOptions";
import type { UpgradePreferences } from "./types";

/** Compact budget / urgency / priorities box shown on a device card. */
export function UpgradeProfileSummary({
  prefs,
}: {
  prefs: UpgradePreferences;
}) {
  return (
    <div className="rounded-[13px] border border-white/[0.09] bg-[#0a1423] p-3">
      <div className="mb-2 flex items-center justify-between gap-[10px]">
        <strong className="text-[12px]">Upgrade profile</strong>
        <span className="text-[14px] font-extrabold text-[#e9e5ff]">
          {money(prefs.budget)}
        </span>
      </div>
      <div className="text-[12px] text-[#8fa0b8]">{prefs.urgency}</div>
      <div className="mt-2 flex flex-wrap gap-[6px]">
        {prefs.priorities.length > 0 ? (
          prefs.priorities.slice(0, 4).map((p) => <Tag key={p}>{p}</Tag>)
        ) : (
          <span className="text-[12px] text-[#8fa0b8]">No priorities set</span>
        )}
      </div>
    </div>
  );
}
