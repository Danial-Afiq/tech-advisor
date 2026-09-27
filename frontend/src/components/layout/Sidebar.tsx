import { BrandMark } from "./BrandMark";
import { ProfileCard } from "./ProfileCard";
import type { ShellUser } from "./ProfileCard";

export type NavKey =
  | "dashboard"
  | "devices"
  | "recommendations"
  | "activity"
  | "settings";

const NAV_ITEMS: { key: NavKey; icon: string; label: string }[] = [
  { key: "dashboard", icon: "⌂", label: "Dashboard" },
  { key: "devices", icon: "▣", label: "My devices" },
  { key: "recommendations", icon: "✦", label: "Recommendations" },
  { key: "activity", icon: "↺", label: "Activity" },
  { key: "settings", icon: "⚙", label: "Settings" },
];

/** App navigation (daisyUI `menu`); collapses to icons below 860px. */
export function Sidebar({
  active,
  user,
  onNavigate,
}: {
  active: NavKey;
  user: ShellUser;
  onNavigate?: (key: NavKey) => void;
}) {
  return (
    <aside className="sticky top-0 z-10 h-screen border-r border-white/[0.09] bg-[#08101f]/80 px-4 py-[22px] backdrop-blur-[16px] max-[860px]:px-[10px] max-[860px]:py-[18px]">
      <div className="px-2 pb-[18px] max-[860px]:flex max-[860px]:justify-center">
        <BrandMark />
      </div>

      <ul className="menu mt-[10px] w-full gap-[6px] p-0">
        {NAV_ITEMS.map((item) => {
          const isActive = item.key === active;
          return (
            <li key={item.key}>
              <button
                type="button"
                aria-current={isActive ? "page" : undefined}
                onClick={() => onNavigate?.(item.key)}
                className={`flex w-full items-center gap-[11px] rounded-[12px] border px-3 py-[11px] text-[14px] font-semibold max-[860px]:justify-center ${
                  isActive
                    ? "border-[#7c5cff]/25 bg-[linear-gradient(90deg,rgba(124,92,255,.18),rgba(124,92,255,.06))] text-[#f7f8ff]"
                    : "border-transparent text-[#95a5bb] hover:bg-white/[0.035] hover:text-white"
                }`}
              >
                <span className="w-5 text-center">{item.icon}</span>
                <span className="max-[860px]:hidden">{item.label}</span>
              </button>
            </li>
          );
        })}
      </ul>

      <div className="absolute right-4 bottom-[18px] left-4 max-[860px]:right-[10px] max-[860px]:left-[10px]">
        <ProfileCard user={user} />
      </div>
    </aside>
  );
}
