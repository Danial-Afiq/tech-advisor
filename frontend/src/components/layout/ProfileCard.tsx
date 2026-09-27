export type ShellUser = { name: string; email: string };

function initials(name: string) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0] ?? "")
    .join("")
    .toUpperCase();
}

/** Signed-in user summary at the foot of the sidebar (daisyUI `avatar`). */
export function ProfileCard({ user }: { user: ShellUser }) {
  return (
    <div className="flex items-center gap-[10px] rounded-[14px] border border-white/[0.09] bg-[#0d1727] p-[11px] max-[860px]:justify-center max-[860px]:p-2">
      <div className="avatar avatar-placeholder">
        <div className="w-[34px] rounded-full bg-[linear-gradient(135deg,#273958,#7c5cff)] text-[13px] font-extrabold">
          <span>{initials(user.name)}</span>
        </div>
      </div>
      <div className="min-w-0 flex-1 max-[860px]:hidden">
        <strong className="block truncate text-[12px]">{user.name}</strong>
        <span className="mt-[2px] block truncate text-[11px] text-[#8fa0b8]">
          {user.email}
        </span>
      </div>
    </div>
  );
}
