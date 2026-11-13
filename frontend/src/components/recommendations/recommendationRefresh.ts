/*
 * Saving or editing a device re-runs its recommendations on the server, in the
 * background (the PUT returns before the new rows exist). The devices page
 * marks that here; the dashboard reads the mark, says it is updating, and
 * re-fetches for a while so the new verdicts replace the old ones without a
 * manual reload.
 *
 * Per tab (sessionStorage) and best effort: when storage is unavailable the
 * dashboard simply behaves as before.
 */

const STORAGE_KEY = "tech-advisor.recommendationsRefreshingUntil";

/** How long the dashboard keeps re-fetching after a device change. */
export const REFRESH_WINDOW_MS = 60 * 1000;

/** How often the dashboard re-fetches inside that window. */
export const REFRESH_POLL_MS = 5 * 1000;

export function markRecommendationsRefreshing(now = Date.now()): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, String(now + REFRESH_WINDOW_MS));
  } catch {
    // Storage blocked: no live refresh, the next page load still shows the new rows.
  }
}

/** When the refresh window ends, or null when no recent device change is pending. */
export function recommendationsRefreshingUntil(now = Date.now()): number | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    const until = raw === null ? NaN : Number(raw);
    if (Number.isFinite(until) && until > now) return until;
    if (raw !== null) window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage blocked: treat as nothing pending.
  }
  return null;
}
