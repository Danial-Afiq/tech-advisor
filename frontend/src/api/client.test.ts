import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch } from "./client";
import { getSession, setSession } from "./session";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("apiFetch", () => {
  it("sends the bearer token and JSON body", async () => {
    setSession({ token: "abc", email: "a@b.com" });
    const fetchMock = vi.fn().mockResolvedValue(json(201, { id: 1 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      apiFetch("/api/devices", { method: "POST", body: { customName: "X" } })
    ).resolves.toEqual({ id: 1 });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).toMatchObject({
      Authorization: "Bearer abc",
      "Content-Type": "application/json",
    });
    expect(init.body).toBe('{"customName":"X"}');
  });

  it("surfaces backend validation messages", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json(400, { purchaseDate: "Purchase date cannot be in the future" })
      )
    );

    await expect(apiFetch("/api/devices")).rejects.toEqual(
      new ApiError(400, "Purchase date cannot be in the future")
    );
  });

  it("clears the session on 401", async () => {
    setSession({ token: "expired", email: "a@b.com" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    await expect(apiFetch("/api/devices")).rejects.toMatchObject({ status: 401 });
    expect(getSession()).toBeNull();
  });
});
