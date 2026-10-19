import type { ComponentProps } from "react";

/** Bell button with an unread count (daisyUI `indicator` + `badge`). */
export function NotificationButton({
  unread,
  onClick,
  ...rest
}: {
  unread: number;
  onClick?: () => void;
} & Omit<ComponentProps<"button">, "type" | "onClick" | "children">) {
  return (
    <button
      type="button"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
      onClick={onClick}
      className="indicator grid h-10 w-10 place-items-center rounded-[12px] border border-white/[0.09] bg-[#0f1b2e] text-[#dbe6f4]"
      {...rest}
    >
      {unread > 0 && (
        <span
          aria-hidden="true"
          className="indicator-item badge badge-error badge-xs h-[18px] min-w-[18px] border-2 border-[#08101f] px-[5px] text-[10px] font-extrabold text-white"
        >
          {unread > 99 ? "99+" : unread}
        </span>
      )}
      <span aria-hidden="true">🔔</span>
    </button>
  );
}
