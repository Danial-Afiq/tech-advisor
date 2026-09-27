/** Bell button with an unread count (daisyUI `indicator` + `badge`). */
export function NotificationButton({
  unread,
  onClick,
}: {
  unread: number;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      aria-label="Notifications"
      onClick={onClick}
      className="indicator grid h-10 w-10 place-items-center rounded-[12px] border border-white/[0.09] bg-[#0f1b2e] text-[#dbe6f4]"
    >
      {unread > 0 && (
        <span className="indicator-item badge badge-error badge-xs h-[18px] min-w-[18px] border-2 border-[#08101f] px-[5px] text-[10px] font-extrabold text-white">
          {unread}
        </span>
      )}
      🔔
    </button>
  );
}
