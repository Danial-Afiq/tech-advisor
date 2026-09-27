import type { ReactNode } from "react";
import { Sidebar } from "./Sidebar";
import type { NavKey } from "./Sidebar";
import type { ShellUser } from "./ProfileCard";
import { ThemeRoot } from "./ThemeRoot";
import { Topbar } from "./Topbar";

/**
 * Signed-in page frame: themed root (`ThemeRoot`), sidebar, top bar and a
 * scrolling content area. Modals and toasts can be rendered as `children`;
 * they are fixed positioned and inherit the theme.
 */
export function AppShell({
  active,
  title,
  user,
  topbarActions,
  onNavigate,
  children,
}: {
  active: NavKey;
  title: string;
  user: ShellUser;
  topbarActions?: ReactNode;
  onNavigate?: (key: NavKey) => void;
  children: ReactNode;
}) {
  return (
    <ThemeRoot>
      <div className="grid min-h-full grid-cols-[250px_minmax(0,1fr)] max-[860px]:grid-cols-[78px_minmax(0,1fr)]">
        <Sidebar active={active} user={user} onNavigate={onNavigate} />
        <main className="min-w-0">
          <Topbar title={title} actions={topbarActions} />
          <div className="mx-auto max-w-[1500px] p-[26px] max-[860px]:px-[14px] max-[860px]:py-[18px]">
            {children}
          </div>
        </main>
      </div>
    </ThemeRoot>
  );
}
