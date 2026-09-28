import { Navigate, Outlet } from "react-router-dom";
import { getSession } from "../../api/session";
import type { Session } from "../../api/session";
import {
  homePathForRole,
  LOGIN_PATH,
} from "../../routing/paths";

export function SessionHomeRedirect() {
  const session = getSession();
  return (
    <Navigate
      to={session ? homePathForRole(session.role) : LOGIN_PATH}
      replace
    />
  );
}

export function SignedOutOnlyRoute() {
  const session = getSession();
  return session ? (
    <Navigate to={homePathForRole(session.role)} replace />
  ) : (
    <Outlet />
  );
}

export function RequireRole({ role }: { role: Session["role"] }) {
  const session = getSession();

  if (!session) return <Navigate to={LOGIN_PATH} replace />;
  if (session.role !== role) {
    return <Navigate to={homePathForRole(session.role)} replace />;
  }

  return <Outlet />;
}
