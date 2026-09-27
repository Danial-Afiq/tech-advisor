import type { ReactNode } from "react";
import { advisorTheme } from "./theme";

/**
 * Full-screen themed page root: daisyUI theme variables, base text styles and
 * the prototype's glowing navy background. Used by `AppShell` and by
 * signed-out pages (login, sign-up) that have no sidebar.
 *
 * It is `fixed inset-0` so it escapes the `#root` width/centering rules in
 * index.css, which are unlayered and would otherwise beat Tailwind utilities.
 */
export function ThemeRoot({ children }: { children: ReactNode }) {
  return (
    <div
      data-theme="dark"
      style={advisorTheme}
      className="fixed inset-0 overflow-y-auto text-left font-sans text-[16px] leading-normal tracking-normal text-[#eef5ff] [background:radial-gradient(circle_at_10%_0%,rgba(124,92,255,.15),transparent_32%),radial-gradient(circle_at_95%_20%,rgba(40,192,255,.08),transparent_28%),#08101f]"
    >
      {children}
    </div>
  );
}
