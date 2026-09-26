import type { ReactNode } from "react";
import { Eyebrow } from "./Eyebrow";

/** daisyUI `modal` with a header (eyebrow, title, close button) and body. */
export function Modal({
  eyebrow,
  title,
  width = "max-w-[650px]",
  onClose,
  children,
}: {
  eyebrow: string;
  title: string;
  width?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      className="modal modal-open z-50 bg-[#01050c]/70 p-[18px] backdrop-blur-[8px]"
    >
      <div
        className={`modal-box max-h-[90vh] w-full ${width} rounded-[22px] border border-white/[0.09] bg-[#0d1727] p-0 shadow-[0_24px_70px_rgba(0,0,0,.33)]`}
      >
        <div className="flex items-center justify-between gap-3 border-b border-white/[0.09] px-5 py-[18px]">
          <div>
            <Eyebrow>{eyebrow}</Eyebrow>
            <h3 className="m-0 text-[18.72px] font-bold">{title}</h3>
          </div>
          <button
            type="button"
            aria-label="Close"
            className="btn btn-ghost btn-sm btn-square text-[22px] font-normal text-[#90a2ba]"
            onClick={onClose}
          >
            ×
          </button>
        </div>
        <div className="p-5">{children}</div>
      </div>
      <button
        type="button"
        aria-label="Close"
        className="modal-backdrop cursor-default"
        onClick={onClose}
      />
    </div>
  );
}

/** Right-aligned button row at the bottom of a modal. */
export function ModalActions({ children }: { children: ReactNode }) {
  return <div className="mt-[10px] flex justify-end gap-2">{children}</div>;
}
