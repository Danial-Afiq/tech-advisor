import { apiFetch } from "./client";
import { clearSession, setSession } from "./session";
import type { Session } from "./session";

/** `POST /api/auth/login` response (backend `dto/LoginResponse.java`). */
type LoginResponse = { token: string; tokenType: string; expiresIn: number; role: "USER" | "ADMIN";};

/** `POST /api/auth/register` response (backend `dto/UserResponse.java`). */
export type UserResponse = {
  id: number;
  email: string;
  role: string;
  createdAt: string;
};

/** Backend rule (`RegisterRequest`): passwords need at least 8 characters. */
export const MIN_PASSWORD_LENGTH = 8;

export async function signIn(email: string, password: string): Promise<Session> {
  const response = await apiFetch<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: { email, password },
    auth: false,
  });
  const session = { token: response.token, email: email.trim().toLowerCase(), role: response.role,};
  setSession(session);
  return session;
}

/** Creates an account. Rejects with the backend's message, e.g. email taken. */
export function register(email: string, password: string) {
  return apiFetch<UserResponse>("/api/auth/register", {
    method: "POST",
    body: { email, password },
    auth: false,
  });
}

/** Creates an account and signs straight into it. */
export async function signUp(email: string, password: string): Promise<Session> {
  await register(email, password);
  return signIn(email, password);
}

export function signOut() {
  clearSession();
}
