import { Button } from "../ui/Button";

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
export function ProfileCard({
  user,
  onSignOut,
}: {
  user: ShellUser;
  onSignOut?: () => void;
}) {
  return (
    <div className="flex items-center gap-[10px] rounded-[14px] border border-white/[0.09] bg-[#0d1727] p-[11px] max-[860px]:flex-col max-[860px]:justify-center max-[860px]:p-2">
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
      {onSignOut && (
        <Button
          variant="danger"
          size="sm"
          aria-label="Sign out"
          title="Sign out"
          className="!h-[32px] !w-[32px] shrink-0 !p-0"
          onClick={onSignOut}
        >
          <svg viewBox="0 0 24 24" fill="none" className="h-[16px] w-[16px]" aria-hidden="true">
            <path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            <path d="M14 8l4 4-4 4M18 12H9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </Button>
      )}
    </div>
  );
}
