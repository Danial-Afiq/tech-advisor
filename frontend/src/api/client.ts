import { API_BASE_URL } from "../config";
import { clearSession, getSession } from "./session";

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/**
 * Turns the backend's error bodies into one readable message. The backend
 * sends `{ "error": "…" }`, or `{ field: message, … }` for validation errors.
 */
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as Record<string, string>;
    if (body.error) return body.error;
    const messages = Object.values(body).filter(Boolean);
    if (messages.length > 0) return messages.join(" ");
  } catch {
    // no JSON body
  }
  if (response.status === 401) return "Your session has expired. Please sign in again.";
  return `Request failed (${response.status}).`;
}

/**
 * JSON fetch against the Spring Boot API. Adds the signed-in user's bearer
 * token unless `auth: false`, and throws `ApiError` on any non-2xx response.
 * A 401 clears the stored session.
 */
export async function apiFetch<T>(
  path: string,
  {
    method = "GET",
    body,
    auth = true,
  }: { method?: string; body?: unknown; auth?: boolean } = {}
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const session = auth ? getSession() : null;
  if (session) headers.Authorization = `Bearer ${session.token}`;

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, `Can't reach the server at ${API_BASE_URL}.`);
  }

  if (!response.ok) {
    if (response.status === 401 && auth) clearSession();
    throw new ApiError(response.status, await errorMessage(response));
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}
