import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DevicesPageTest from "./DevicesPageTest";
import { ApiError } from "../api/client";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  signOut: vi.fn(),
  listDevices: vi.fn(),
  createDevice: vi.fn(),
  updateDevice: vi.fn(),
  deleteDevice: vi.fn(),
  listSmartphones: vi.fn(),
}));

vi.mock("../api/notifications", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/notifications")>()),
  getUnreadNotificationCount: vi.fn().mockResolvedValue(0),
  listNotifications: vi.fn().mockResolvedValue({ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
}));

vi.mock("../api/session", () => ({
  getSession: mocks.getSession,
}));

vi.mock("../api/auth", () => ({
  signOut: mocks.signOut,
}));

vi.mock("../api/devices", () => ({
  listDevices: mocks.listDevices,
  createDevice: mocks.createDevice,
  updateDevice: mocks.updateDevice,
  deleteDevice: mocks.deleteDevice,
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
  budget: null as number | null,
  currency: null as string | null,
  upgradeUrgency: "NOT_URGENT",
  brandFlexibility: "FLEXIBLE",
  priorities: '{"value":1,"performance":1,"longevity":1}',
  painPoints: '""',
  notes: "",
  current: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getSession.mockReturnValue({ token: "token", email: "user@example.com" });
  mocks.signOut.mockImplementation(() => undefined);
  mocks.listDevices.mockResolvedValue([]);
  mocks.createDevice.mockResolvedValue(apiDevice);
  mocks.listSmartphones.mockResolvedValue([]);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});

describe("DevicesPageTest", () => {
  it("signs out from the profile card", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("No devices yet")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(mocks.signOut).toHaveBeenCalled();
    expect(await screen.findByText("login route")).toBeInTheDocument();
  });

  it("loads an existing session and handles a 401 by returning to login", async () => {
    mocks.listDevices.mockRejectedValue(new ApiError(401, "expired"));

    renderPage();

    expect(screen.getByText("Loading your devices…")).toBeInTheDocument();
    expect(await screen.findByText("login route")).toBeInTheDocument();
  });

  it("adds a signed-in device and moves to upgrade preferences", async () => {
    const user = userEvent.setup();
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
    // The device form doesn't collect a budget, so none is invented.
    expect(mocks.createDevice).toHaveBeenCalledWith(
      expect.objectContaining({ budget: null, currency: null })
    );
    expect(await screen.findByText("Device saved")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Test Laptop" })).toBeInTheDocument();

    // Step 2 saves the budget to the new device.
    mocks.updateDevice.mockResolvedValue({ ...apiDevice, budget: 2500, currency: "SGD" });
    fireEvent.change(screen.getByLabelText("Maximum budget"), { target: { value: "2500" } });
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    expect(await screen.findByText("Upgrade profile saved")).toBeInTheDocument();
    expect(mocks.updateDevice).toHaveBeenCalledWith(
      "9",
      expect.objectContaining({ customName: "Test Laptop", budget: 2500, currency: "SGD" })
    );
    expect(screen.getByText("S$2,500")).toBeInTheDocument();
  });

  it("shows the saved budget after a reload and saves a change through the backend", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([{ ...apiDevice, budget: 1800, currency: "SGD" }]);
    mocks.updateDevice.mockResolvedValue({ ...apiDevice, budget: 3000, currency: "SGD" });
    renderPage();

    // The card and the modal start from the saved value, not a default.
    expect(await screen.findByText("S$1,800")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Upgrade preferences" }));
    expect(screen.getByLabelText("Maximum budget")).toHaveValue("1800");

    fireEvent.change(screen.getByLabelText("Maximum budget"), { target: { value: "3000" } });
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));

    await waitFor(() =>
      expect(mocks.updateDevice).toHaveBeenCalledWith(
        "9",
        expect.objectContaining({
          customName: "Test Laptop",
          condition: "Good",
          satisfactionScore: 75,
          budget: 3000,
          currency: "SGD",
        })
      )
    );
    expect(await screen.findByText(/upgrade preferences for Test Laptop were saved to your account/)).toBeInTheDocument();
    expect(screen.getByText("S$3,000")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows a device without a saved budget as having none", async () => {
    mocks.listDevices.mockResolvedValue([apiDevice]);
    renderPage();

    expect(await screen.findByText("No budget set")).toBeInTheDocument();
  });

  it("persists the full profile and restores it after a reload without changing another device", async () => {
    const user = userEvent.setup();
    const saved = {
      ...apiDevice,
      budget: 1500,
      currency: "USD",
      upgradeUrgency: "URGENT",
      brandFlexibility: "NOT_FLEXIBLE",
      priorities: '{"camera":4,"battery":1}',
      painPoints: '"Battery drains quickly"',
      notes: "Avoid launch pricing.",
    };
    const other = { ...apiDevice, id: 10, customName: "Second phone" };
    mocks.listDevices.mockResolvedValue([
      { ...apiDevice, budget: 1500, currency: "USD", priorities: '{"camera":4}' }, other,
    ]);
    mocks.updateDevice.mockResolvedValue(saved);
    const page = renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getAllByRole("button", { name: "Upgrade preferences" })[0]);
    await user.click(screen.getByRole("button", { name: "Battery life" }));
    await user.selectOptions(screen.getByLabelText("Upgrade urgency"), "URGENT");
    await user.selectOptions(screen.getByLabelText("Brand flexibility for this replacement"), "NOT_FLEXIBLE");
    await user.type(screen.getByLabelText("Current pain points"), "Battery drains quickly");
    await user.type(screen.getByLabelText("Anything else for this device?"), "Avoid launch pricing.");
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));

    await waitFor(() => expect(mocks.updateDevice).toHaveBeenCalledWith("9", expect.objectContaining({
      budget: 1500, currency: "USD", upgradeUrgency: "URGENT", brandFlexibility: "NOT_FLEXIBLE",
      painPoints: saved.painPoints, notes: saved.notes,
    })));
    const request = mocks.updateDevice.mock.calls[0][1];
    expect(JSON.parse(request.priorities)).toEqual({ camera: 4, battery: 1 });
    expect(screen.getByText("No budget set")).toBeInTheDocument();
    expect(mocks.updateDevice).toHaveBeenCalledTimes(1);

    page.unmount();
    mocks.listDevices.mockResolvedValue([saved, other]);
    renderPage();
    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getAllByRole("button", { name: "Upgrade preferences" })[0]);
    expect(screen.getByLabelText("Maximum budget")).toHaveValue("1500");
    expect(screen.getByLabelText("Upgrade urgency")).toHaveValue("URGENT");
    expect(screen.getByLabelText("Brand flexibility for this replacement")).toHaveValue("NOT_FLEXIBLE");
    expect(screen.getByRole("button", { name: "Camera" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Battery life" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("Current pain points")).toHaveValue("Battery drains quickly");
    expect(screen.getByLabelText("Anything else for this device?")).toHaveValue("Avoid launch pricing.");
  });

  it("keeps the upgrade profile open when the backend rejects the budget", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.updateDevice.mockRejectedValue(new ApiError(400, "Budget must not be negative"));
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Upgrade preferences" }));
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));

    expect(await screen.findByText("Budget must not be negative")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save upgrade profile" })).toBeInTheDocument();
    expect(screen.queryByText("Upgrade profile saved")).not.toBeInTheDocument();
    expect(screen.getByText("No budget set")).toBeInTheDocument();
  });

  it("saves an older budget-only profile without resending unsupported empty pain points", async () => {
    const user = userEvent.setup();
    const legacy = {
      ...apiDevice, budget: 1800, currency: "USD", upgradeUrgency: null,
      brandFlexibility: null, priorities: "{}", painPoints: "{}", notes: null,
    };
    mocks.listDevices.mockResolvedValue([legacy]);
    mocks.updateDevice.mockResolvedValue({
      ...legacy, upgradeUrgency: "NOT_URGENT", brandFlexibility: "FLEXIBLE", painPoints: '""', notes: "",
    });
    renderPage();

    await screen.findByText("USD 1,800");
    await user.click(screen.getByRole("button", { name: "Upgrade preferences" }));
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    expect(mocks.updateDevice).not.toHaveBeenCalled();
    expect(screen.getByText("Choose upgrade urgency and brand flexibility before saving.")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Upgrade urgency"), "NOT_URGENT");
    await user.selectOptions(screen.getByLabelText("Brand flexibility for this replacement"), "FLEXIBLE");
    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    await waitFor(() => expect(mocks.updateDevice).toHaveBeenCalledWith("9", expect.objectContaining({
      budget: 1800, currency: "USD", upgradeUrgency: "NOT_URGENT", brandFlexibility: "FLEXIBLE",
      priorities: "{}", painPoints: '""', notes: "",
    })));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
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
    mocks.listDevices.mockResolvedValue([linked]);
    mocks.listSmartphones.mockResolvedValue([pixel]);
    mocks.createDevice.mockResolvedValue({ ...linked, id: 4, specOverrides: "{}" });
    mocks.updateDevice.mockResolvedValue({
      ...linked,
      id: 4,
      specOverrides: "{}",
      budget: 1200,
      currency: "SGD",
    });
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

  it("saves edits to a signed-in device through the backend", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.updateDevice.mockResolvedValue({
      ...apiDevice,
      customName: "Test Laptop Pro",
      condition: "Fair",
    });
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Edit device" }));
    const model = screen.getByLabelText("Model / configuration");
    await user.clear(model);
    await user.type(model, "Test Laptop Pro");
    await user.selectOptions(screen.getByLabelText("Condition"), "Fair");
    await user.click(screen.getByRole("button", { name: "Save device" }));

    await waitFor(() =>
      expect(mocks.updateDevice).toHaveBeenCalledWith(
        "9",
        expect.objectContaining({ customName: "Test Laptop Pro", condition: "Fair" })
      )
    );
    expect(await screen.findByText("Test Laptop Pro was updated in your account.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Test Laptop Pro/ })).toBeInTheDocument();
  });

  it("prefills the edit form with a linked phone's type and saved details", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([{
      ...apiDevice,
      productId: 5,
      productBrand: "Apple",
      productModelName: "iPhone 13",
      productCategory: "SMARTPHONE",
      customName: null,
      condition: "FAIR",
    }]);
    mocks.listSmartphones.mockResolvedValue([{
      id: 5, brand: "Apple", modelName: "iPhone 13", releaseDate: null, status: "VERIFIED",
      chipset: "A15 Bionic", ramGb: 4, cpuGhz: null, storageGb: 128, batteryMah: 3240,
      wiredChargingWatts: null, wirelessChargingWatts: null, displaySizeInches: null,
      refreshRateHz: null, weightG: null, cameraSpecs: null, pixelDensity: null,
      ipRating: null, os: null, softwareSupportYears: null, latestPrice: null, currency: null,
    }]);
    renderPage();

    await screen.findByRole("heading", { name: /iPhone 13/ });
    await screen.findByText(/listed/); // catalogue specs have reached the card
    await user.click(screen.getByRole("button", { name: "Edit device" }));

    expect(screen.getByLabelText("Device type")).toHaveValue("Phone");
    expect(screen.getByLabelText("Brand")).toHaveValue("Apple");
    expect(screen.getByLabelText("Model / configuration")).toHaveValue("iPhone 13");
    expect(screen.getByLabelText("Condition")).toHaveValue("Fair");
    expect(screen.getByLabelText("Purchase date")).toHaveValue("2025-01-01");
    expect(screen.getByDisplayValue("3240")).toBeInTheDocument();
    expect(screen.getByDisplayValue("A15 Bionic")).toBeInTheDocument();
  });

  it("knows a linked device is a phone before the catalogue has loaded", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([{
      ...apiDevice,
      productId: 5,
      productBrand: "Apple",
      productModelName: "iPhone 13",
      productCategory: "SMARTPHONE",
      customName: null,
    }]);
    mocks.listSmartphones.mockReturnValue(new Promise(() => {}));
    renderPage();

    await screen.findByRole("heading", { name: /iPhone 13/ });
    await user.click(screen.getByRole("button", { name: "Edit device" }));

    expect(screen.getByLabelText("Device type")).toHaveValue("Phone");
    expect(screen.getByRole("heading", { name: /Specifications/ })).toBeInTheDocument();
  });

  it("keeps an unlinked device's unknown type instead of defaulting to Desktop PC", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Edit device" }));

    expect(screen.getByLabelText("Device type")).toHaveValue("Other");
    expect(screen.getByLabelText("Model / configuration")).toHaveValue("Test Laptop");
  });

  it("removes a signed-in device through the backend", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.deleteDevice.mockResolvedValue(undefined);
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Remove" }));

    expect(mocks.deleteDevice).toHaveBeenCalledWith("9");
    expect(await screen.findByText("Device removed")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Test Laptop/ })).not.toBeInTheDocument();
  });

  it("keeps the device and says so when the backend can't remove it", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.deleteDevice.mockRejectedValue(new ApiError(0, "Can't reach the server."));
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Remove" }));

    expect(await screen.findByText("Couldn't remove Test Laptop")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Test Laptop/ })).toBeInTheDocument();
    expect(screen.queryByText("Device removed")).not.toBeInTheDocument();
  });

  it("drops a device the backend says is already gone", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.deleteDevice.mockRejectedValue(new ApiError(404, "Device not found"));
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Remove" }));

    expect(await screen.findByText("Device removed")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /Test Laptop/ })).not.toBeInTheDocument();
  });

  it("keeps the edit form open when the backend rejects an edit", async () => {
    const user = userEvent.setup();
    mocks.listDevices.mockResolvedValue([apiDevice]);
    mocks.updateDevice.mockRejectedValue(new ApiError(400, "Invalid device"));
    renderPage();

    await screen.findByRole("heading", { name: /Test Laptop/ });
    await user.click(screen.getByRole("button", { name: "Edit device" }));
    await user.click(screen.getByRole("button", { name: "Save device" }));

    expect(await screen.findByText("Invalid device")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save device" })).toBeInTheDocument();
    expect(screen.queryByText("Device updated")).not.toBeInTheDocument();
  });
});
