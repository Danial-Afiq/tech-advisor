/**
 * Where the signed-in user's JWT lives.
 *
 * TEMPORARY: the project has not decided on frontend token storage yet (the
 * Login page is a placeholder). sessionStorage keeps the token for the tab's
 * lifetime only. Everything reads/writes through these three functions, so
 * switching to another mechanism (e.g. an httpOnly cookie) is a one-file change.
 */

export type Session = { token: string; email: string };

const KEY = "techAdvisor.session";

export function getSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null; // storage blocked or corrupted: treat as signed out
  }
}

export function setSession(session: Session) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // storage blocked: the session just won't survive a reload
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // nothing stored
  }
}
