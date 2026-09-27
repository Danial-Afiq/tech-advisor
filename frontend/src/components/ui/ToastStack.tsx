export type Toast = { id: string; title: string; text: string; icon: string };

/** Bottom-right daisyUI `toast` stack. Pair with the `useToasts` hook. */
export function ToastStack({ toasts }: { toasts: Toast[] }) {
  return (
    <div className="toast toast-end toast-bottom pointer-events-none z-[80] p-[18px]">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role="status"
          className="alert flex w-[min(370px,calc(100vw-36px))] items-start gap-[11px] rounded-[15px] border border-white/[0.09] bg-[#111d31] px-[14px] py-[13px] text-left shadow-[0_24px_70px_rgba(0,0,0,.33)]"
        >
          <div className="grid h-[31px] w-[31px] shrink-0 place-items-center rounded-[10px] bg-[#2ed39a]/12 text-[#67e5b5]">
            {toast.icon}
          </div>
          <div>
            <strong className="block text-[12px]">{toast.title}</strong>
            <span className="mt-[3px] block text-[11px] leading-[1.45] text-[#8fa0b8]">
              {toast.text}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}
