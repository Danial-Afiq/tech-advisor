import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AdminCatalogue from "./AdminCatalogue";
import { ApiError } from "../api/client";
import type { SmartphoneCatalogueItem } from "../api/catalogue";

const mocks = vi.hoisted(() => ({
  listSmartphones: vi.fn(),
  createSmartphone: vi.fn(),
  updateSmartphone: vi.fn(),
  deleteSmartphone: vi.fn(),
  listDatabaseTables: vi.fn(),
}));

vi.mock("../api/session", () => ({
  getSession: () => ({ token: "t", email: "admin@example.com", role: "ADMIN" }),
}));
vi.mock("../api/auth", () => ({ signOut: vi.fn() }));
vi.mock("../api/catalogue", () => ({
  listSmartphones: mocks.listSmartphones,
  createSmartphone: mocks.createSmartphone,
  updateSmartphone: mocks.updateSmartphone,
  deleteSmartphone: mocks.deleteSmartphone,
}));
vi.mock("../api/database", () => ({ listDatabaseTables: mocks.listDatabaseTables }));

const phone = (id: number, brand: string, modelName: string): SmartphoneCatalogueItem => ({
  id,
  brand,
  modelName,
  releaseDate: null,
  status: "VERIFIED",
  chipset: null,
  ramGb: 8,
  cpuGhz: null,
  storageGb: 128,
  batteryMah: null,
  wiredChargingWatts: null,
  wirelessChargingWatts: null,
  displaySizeInches: null,
  refreshRateHz: null,
  weightG: null,
  cameraSpecs: null,
  pixelDensity: null,
  ipRating: null,
  os: "Android 14",
  softwareSupportYears: null,
});

/** 30 phones: Brand 01 Model 01 … Brand 30 Model 30. */
const catalogue = Array.from({ length: 30 }, (_, i) => {
  const n = String(i + 1).padStart(2, "0");
  return phone(i + 1, `Brand ${n}`, `Model ${n}`);
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminCatalogue />
    </MemoryRouter>
  );

const rowNames = () =>
  screen
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell")[2].textContent);

describe("AdminCatalogue", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listSmartphones.mockResolvedValue(catalogue);
  });

  it("lists entries 25 per page and pages through them", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("Showing 1–25 of 30")).toBeInTheDocument();
    expect(rowNames()).toHaveLength(25);

    await user.click(screen.getByRole("button", { name: "Page 2" }));
    expect(screen.getByText("Showing 26–30 of 30")).toBeInTheDocument();
    expect(rowNames()[0]).toContain("Model 26");
  });

  it("searches, filters and sorts", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.type(screen.getByRole("searchbox", { name: "Search" }), "model 07");
    expect(rowNames()).toEqual([expect.stringContaining("Model 07")]);
    expect(screen.getByText(/filtered from 30/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "Brand Z–A");
    expect(rowNames()[0]).toContain("Model 30");

    await user.selectOptions(screen.getByRole("combobox", { name: "Brand" }), "Brand 03");
    expect(rowNames()).toEqual([expect.stringContaining("Model 03")]);
  });

  it("adds an entry through the form", async () => {
    const user = userEvent.setup();
    mocks.createSmartphone.mockResolvedValue(phone(31, "Nothing", "Phone 3"));
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "+ Add smartphone" }));
    const dialog = screen.getByRole("dialog", { name: "Add smartphone" });
    await user.type(within(dialog).getByRole("textbox", { name: "Brand" }), "Nothing");
    await user.type(within(dialog).getByRole("textbox", { name: "Model" }), "Phone 3");
    await user.click(within(dialog).getByRole("button", { name: "Add smartphone" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(mocks.createSmartphone).toHaveBeenCalledWith(
      expect.objectContaining({ brand: "Nothing", modelName: "Phone 3", status: "VERIFIED", ramGb: null })
    );
    expect(screen.getByText("Showing 1–25 of 31")).toBeInTheDocument();
  });

  it("keeps the form open with the server's message when a save fails", async () => {
    const user = userEvent.setup();
    mocks.updateSmartphone.mockRejectedValue(
      new ApiError(400, "A catalogue entry with this brand and model already exists")
    );
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "Edit Brand 01 Model 01" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Brand 01 Model 01" });
    await user.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("already exists");
    expect(mocks.updateSmartphone).toHaveBeenCalledWith(1, expect.objectContaining({ ramGb: 8 }));
  });

  it("deletes an entry after confirmation", async () => {
    const user = userEvent.setup();
    mocks.deleteSmartphone.mockResolvedValue(undefined);
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "Delete Brand 01 Model 01" }));
    await user.click(screen.getByRole("button", { name: "Delete entry" }));

    await waitFor(() => expect(screen.getByText("Showing 1–25 of 29")).toBeInTheDocument());
    expect(mocks.deleteSmartphone).toHaveBeenCalledWith(1);
  });

  it("shows every database table as a card", async () => {
    const user = userEvent.setup();
    mocks.listDatabaseTables.mockResolvedValue([
      {
        name: "phone",
        rowCount: 30,
        columns: [
          { name: "product_id", type: "bigint", nullable: false, primaryKey: true, references: "products" },
        ],
      },
      { name: "users", rowCount: 1, columns: [] },
    ]);
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "Show visualisation of all tables" }));
    const panel = await screen.findByRole("complementary", { name: "Database tables" });
    expect(await within(panel).findByRole("article", { name: "Table phone" })).toHaveTextContent(
      "30 rows"
    );
    expect(within(panel).getByText("→ products")).toBeInTheDocument();
    expect(within(panel).getByRole("article", { name: "Table users" })).toHaveTextContent("1 row");

    await user.click(screen.getByRole("button", { name: "Hide visualisation of all tables" }));
    expect(screen.queryByRole("complementary", { name: "Database tables" })).not.toBeInTheDocument();
  });
});
