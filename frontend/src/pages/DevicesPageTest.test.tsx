import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DevicesPageTest from "./DevicesPageTest";
import { ApiError } from "../api/client";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
  listDevices: vi.fn(),
  createDevice: vi.fn(),
  listSmartphones: vi.fn(),
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/auth", () => ({
  signIn: mocks.signIn,
  signOut: mocks.signOut,
}));

vi.mock("../api/devices", () => ({
  listDevices: mocks.listDevices,
  createDevice: mocks.createDevice,
}));

vi.mock("../api/catalogue", () => ({
  listSmartphones: mocks.listSmartphones,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/devices"]}>
      <Routes>
        <Route path="/devices" element={<DevicesPageTest />} />
        <Route path="/login" element={<p>login route</p>} />
      </Routes>
    </MemoryRouter>
  );
}

const apiDevice = {
  id: 9,
  productId: null,
  productBrand: null,
  productModelName: null,
  customName: "Test Laptop",
  purchaseDate: "2025-01-01",
  condition: "Good",
  satisfactionScore: 75,
  useCases: "[]",
  specOverrides: "{}",
  current: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getSession.mockReturnValue(null);
  mocks.signIn.mockResolvedValue({ token: "token", email: "user@example.com" });
  mocks.signOut.mockImplementation(() => undefined);
  mocks.listDevices.mockResolvedValue([]);
  mocks.createDevice.mockResolvedValue(apiDevice);
  mocks.listSmartphones.mockResolvedValue([]);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});

describe("DevicesPageTest", () => {
  it("covers signed-out demo, edit, remove and preference flows", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByText(/Demo mode/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Apple iPhone 13 Pro Max/ })).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Edit device" })[0]);
    expect(screen.getByRole("dialog", { name: "Update device details" })).toBeInTheDocument();
    const usage = screen.getByLabelText("Main usage");
    await user.clear(usage);
    await user.type(usage, "Updated usage");
    await user.click(screen.getByRole("button", { name: "Save device" }));
    expect(await screen.findByText("Device updated")).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Upgrade preferences" })[0]);
    expect(screen.getByRole("dialog", { name: /iPhone 13 Pro Max/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    expect(await screen.findByText("Upgrade profile saved")).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Remove" })[0]);
    expect(window.confirm).toHaveBeenCalled();
    expect(await screen.findByText("Device removed")).toBeInTheDocument();
  });

  it("signs in and loads account devices", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await user.type(screen.getByLabelText("Email"), "user@example.com");
    await user.type(screen.getByLabelText("Password"), "password123");
    const signInDialog = screen.getByRole("dialog", { name: "Sign in to save devices" });
    await user.click(signInDialog.querySelector('button[type="submit"]') as HTMLButtonElement);

    expect(mocks.signIn).toHaveBeenCalledWith("user@example.com", "password123");
    await waitFor(() => expect(mocks.listDevices).toHaveBeenCalled());
    expect(await screen.findByText(/Signed in as/)).toBeInTheDocument();
    expect(await screen.findByText("No devices yet")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(mocks.signOut).toHaveBeenCalled();
  });

  it("loads an existing session and handles a 401 by returning to login", async () => {
    mocks.getSession.mockReturnValue({ token: "token", email: "user@example.com" });
    mocks.listDevices.mockRejectedValue(new ApiError(401, "expired"));

    renderPage();

    expect(screen.getByText("Loading your devices…")).toBeInTheDocument();
    expect(await screen.findByText("login route")).toBeInTheDocument();
  });

  it("adds a signed-in device and moves to upgrade preferences", async () => {
    const user = userEvent.setup();
    mocks.getSession.mockReturnValue({ token: "token", email: "user@example.com" });
    mocks.listDevices.mockResolvedValue([]);
    renderPage();

    await screen.findByText("No devices yet");
    await user.click(screen.getByRole("button", { name: "Add device" }));

    await user.selectOptions(screen.getByLabelText("Device type"), "Laptop");
    await user.type(screen.getByLabelText("Brand"), "Dell");
    await user.type(screen.getByLabelText("Model / configuration"), "XPS 13");
    fireEvent.change(screen.getByLabelText(/Current satisfaction/), { target: { value: "85" } });
    await user.type(screen.getByLabelText("Main usage"), "Coding");
    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));

    await waitFor(() => expect(mocks.createDevice).toHaveBeenCalled());
    expect(await screen.findByText("Device saved")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Test Laptop" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    expect(await screen.findByText("Upgrade profile saved")).toBeInTheDocument();
  });

  it("adds a catalogue phone and shows catalogue specs on linked devices", async () => {
    const user = userEvent.setup();
    const pixel = {
      id: 5,
      brand: "Google",
      modelName: "Pixel 9",
      chipset: "Google Tensor G4",
      ramGb: 12,
      storageGb: 128,
    };
    const linked = {
      ...apiDevice,
      id: 3,
      productId: 5,
      productBrand: "Google",
      productModelName: "Pixel 9",
      customName: null,
      specOverrides: '{"storage_gb":256}',
    };
    mocks.getSession.mockReturnValue({ token: "token", email: "user@example.com" });
    mocks.listDevices.mockResolvedValue([linked]);
    mocks.listSmartphones.mockResolvedValue([pixel]);
    mocks.createDevice.mockResolvedValue({ ...linked, id: 4, specOverrides: "{}" });
    renderPage();

    await screen.findByRole("heading", { name: /Google Pixel 9/ });
    await user.click(screen.getByRole("button", { name: /Add device/ }));
    await user.type(screen.getByRole("combobox", { name: "Model / configuration" }), "pixel");
    await user.click(await screen.findByRole("option", { name: /Google Pixel 9/ }));
    expect(screen.getByLabelText("Chipset")).toHaveValue("Google Tensor G4");
    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));

    await waitFor(() =>
      expect(mocks.createDevice).toHaveBeenCalledWith(
        expect.objectContaining({ productId: 5, customName: null, specOverrides: "{}" })
      )
    );
    await user.click(await screen.findByRole("button", { name: "Save upgrade profile" }));

    // Both linked devices show catalogue specs; the loaded one keeps its override.
    expect(screen.getAllByText("Google Tensor G4")).toHaveLength(2);
    expect(screen.getByText("256 GB")).toBeInTheDocument();
    expect(screen.getByText("128 GB")).toBeInTheDocument();
  });
});
