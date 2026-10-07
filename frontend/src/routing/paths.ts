import type { Session } from "../api/session";

export const LOGIN_PATH = "/login";
export const USER_HOME_PATH = "/devices";
export const ADMIN_HOME_PATH = "/admin/ingestion";
export const ADMIN_CATALOGUE_PATH = "/admin/catalogue";

export function homePathForRole(role: Session["role"]) {
  return role === "ADMIN" ? ADMIN_HOME_PATH : USER_HOME_PATH;
}
