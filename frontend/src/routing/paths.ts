import type { Session } from "../api/session";

export const LOGIN_PATH = "/login";
export const DASHBOARD_PATH = "/dashboard";
export const DEVICES_PATH = "/devices";
export const USER_HOME_PATH = DASHBOARD_PATH;
export const ADMIN_HOME_PATH = "/admin/ingestion";

export function homePathForRole(role: Session["role"]) {
  return role === "ADMIN" ? ADMIN_HOME_PATH : USER_HOME_PATH;
}