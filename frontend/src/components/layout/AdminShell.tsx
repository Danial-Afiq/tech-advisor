import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { signOut } from "../../api/auth";
import {
  ADMIN_CATALOGUE_PATH,
  ADMIN_HOME_PATH,
  LOGIN_PATH,
} from "../../routing/paths";
import { AppShell } from "./AppShell";
import type { NavItem, NavKey } from "./Sidebar";

type AdminNavKey = "ingestion" | "catalogue";

const ADMIN_NAV_ITEMS: NavItem[] = [
  { key: "ingestion", icon: "↻", label: "Manual ingestion" },
  { key: "catalogue", icon: "▣", label: "Catalogue" },
];

const ADMIN_PATHS: Record<AdminNavKey, string> = {
  ingestion: ADMIN_HOME_PATH,
  catalogue: ADMIN_CATALOGUE_PATH,
};

export function AdminShell({
  active,
  title,
  email,
  children,
}: {
  active: AdminNavKey;
  title: string;
  email: string;
  children: ReactNode;
}) {
  const navigate = useNavigate();

  const handleNavigate = (key: NavKey) => {
    if (key === "ingestion" || key === "catalogue") {
      navigate(ADMIN_PATHS[key]);
    }
  };

  const handleSignOut = () => {
    signOut();
    navigate(LOGIN_PATH, { replace: true });
  };

  return (
    <AppShell
      active={active}
      title={title}
      user={{ name: email.split("@")[0], email }}
      navItems={ADMIN_NAV_ITEMS}
      onNavigate={handleNavigate}
      onSignOut={handleSignOut}
    >
      {children}
    </AppShell>
  );
}
