import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { getSession } from "../api/session";
import Login from "./Login";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function renderLogin(url = "/login") {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/DevicesPageTest" element={<p>devices page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("Login page", () => {
  it("signs up, logs in and goes to the devices page", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(201, { id: 2, email: "new@example.com" }))
      .mockResolvedValueOnce(json(200, { token: "t", tokenType: "Bearer", expiresIn: 3600 }));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    renderLogin("/login?mode=signup");
    await user.type(screen.getByLabelText("Email"), "new@example.com");
    await user.type(screen.getByLabelText(/^Password/), "password123");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("devices page")).toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      expect.stringMatching(/\/api\/auth\/register$/),
      expect.stringMatching(/\/api\/auth\/login$/),
    ]);
    expect(getSession()).toEqual({ token: "t", email: "new@example.com" });
  });

  it("shows the backend's error on a failed log in", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(401, { error: "Invalid email or password" }))
    );
    const user = userEvent.setup();

    renderLogin();
    await user.type(screen.getByLabelText("Email"), "jerma@example.com");
    await user.type(screen.getByLabelText(/^Password/), "wrong-pass");
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Invalid email or password"
    );
    expect(getSession()).toBeNull();
  });
});
