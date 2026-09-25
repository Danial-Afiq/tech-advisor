import type { ButtonHTMLAttributes } from "react";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "md" | "sm";

const SIZES: Record<ButtonSize, string> = {
  md: "h-auto min-h-0 rounded-[12px] px-[14px] py-[11px] text-[14px]",
  sm: "btn-sm h-auto min-h-0 rounded-[10px] px-[10px] py-[8px] text-[12px]",
};

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    "btn-primary border-0 bg-[linear-gradient(135deg,#7c5cff,#5f85ff)] shadow-[0_10px_24px_rgba(124,92,255,.23)]",
  secondary:
    "border-white/[0.09] bg-[#111d31] text-[#eef5ff] shadow-none hover:bg-[#16243b]",
  ghost:
    "btn-ghost border-white/[0.09] text-[#c6d2e2] hover:bg-white/[0.04]",
  danger:
    "border-[#ff6d7a]/25 bg-[#ff6d7a]/12 text-[#ff9aa4] shadow-none hover:bg-[#ff6d7a]/18",
};

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Shows a spinner and disables the button, e.g. while a request runs. */
  loading?: boolean;
};

/** daisyUI `btn` styled with the prototype's four button variants. */
export function Button({
  variant = "secondary",
  size = "md",
  type = "button",
  loading = false,
  disabled,
  className = "",
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={`btn font-bold hover:-translate-y-px disabled:opacity-60 ${SIZES[size]} ${VARIANTS[variant]} ${className}`}
      {...rest}
    >
      {loading && <span className="loading loading-spinner loading-xs" />}
      {children}
    </button>
  );
}
