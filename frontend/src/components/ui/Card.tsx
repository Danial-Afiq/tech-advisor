import type { HTMLAttributes } from "react";

/** daisyUI `card` with the prototype's dark gradient surface. */
export function Card({
  className = "",
  ...rest
}: HTMLAttributes<HTMLElement>) {
  return (
    <article
      className={`card rounded-[18px] border border-white/[0.09] bg-[linear-gradient(180deg,rgba(17,29,49,.92),rgba(12,22,38,.92))] shadow-[0_12px_34px_rgba(0,0,0,.14)] ${className}`}
      {...rest}
    />
  );
}
