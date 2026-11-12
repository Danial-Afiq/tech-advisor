import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AdminCatalogue from "./AdminCatalogue";
import { ApiError } from "../api/client";
import type { GpuCatalogueItem, SmartphoneCatalogueItem } from "../api/catalogue";

const mocks = vi.hoisted(() => ({
  listSmartphones: vi.fn(),
  createSmartphone: vi.fn(),
  updateSmartphone: vi.fn(),
  deleteSmartphone: vi.fn(),
  listSmartphonePrices: vi.fn(),
  listSmartphoneBenchmarks: vi.fn(),
  listDatabaseTables: vi.fn(),
  listGpus: vi.fn(),
  createGpu: vi.fn(),
  updateGpu: vi.fn(),
  deleteGpu: vi.fn(),
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
  listSmartphonePrices: mocks.listSmartphonePrices,
  listSmartphoneBenchmarks: mocks.listSmartphoneBenchmarks,
  listGpus: mocks.listGpus,
  createGpu: mocks.createGpu,
  updateGpu: mocks.updateGpu,
  deleteGpu: mocks.deleteGpu,
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
  latestPrice: null,
  currency: null,
});

/** 30 phones: Brand 01 Model 01 … Brand 30 Model 30. */
const catalogue = Array.from({ length: 30 }, (_, i) => {
  const n = String(i + 1).padStart(2, "0");
  return phone(i + 1, `Brand ${n}`, `Model ${n}`);
});

const gpu = (id: number, brand: string, modelName: string, patch: Partial<GpuCatalogueItem> = {}): GpuCatalogueItem => ({
  id,
  brand,
  modelName,
  releaseDate: "2024-01-15",
  status: "VERIFIED",
  baseClockMhz: 1500,
  boostClockMhz: 2500,
  vramGb: "12",
  memoryType: "GDDR6",
  memoryBusBits: 192,
  tdpWatts: 200,
  architecture: "Ada Lovelace",
  source: "techspot-gpu-specs",
  sourceUrl: null,
  fetchedAt: "2026-10-30T00:00:00Z",
  latestPrice: null,
  currency: null,
  ...patch,
});

const renderPage = (entry = "/admin/catalogue") =>
  render(
    <MemoryRouter initialEntries={[entry]}>
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
    mocks.listSmartphonePrices.mockResolvedValue([]);
    mocks.listSmartphoneBenchmarks.mockResolvedValue([]);
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

  it("shows the latest price, or a dash without one", async () => {
    mocks.listSmartphones.mockResolvedValue([
      { ...catalogue[0], latestPrice: 849, currency: "SGD" },
      catalogue[1],
    ]);
    renderPage();
    await screen.findByText("Showing 1–2 of 2");

    const [priced, unpriced] = screen.getAllByRole("row").slice(1);
    expect(within(priced).getAllByRole("cell")[5]).toHaveTextContent("849.00");
    expect(within(unpriced).getAllByRole("cell")[5]).toHaveTextContent("—");
  });

  it("filters by a preset price range, then an edited one", async () => {
    const user = userEvent.setup();
    mocks.listSmartphones.mockResolvedValue([
      { ...catalogue[0], latestPrice: 450, currency: "SGD" },
      { ...catalogue[1], latestPrice: 899, currency: "SGD" },
      { ...catalogue[2], latestPrice: 1299, currency: "SGD" },
      catalogue[3],
    ]);
    renderPage();
    await screen.findByText("Showing 1–4 of 4");

    await user.selectOptions(screen.getByRole("combobox", { name: "Price range" }), "500 – 1,000");
    expect(rowNames()).toEqual([expect.stringContaining("Model 02")]);
    expect(screen.getByRole("spinbutton", { name: "Min price" })).toHaveValue(500);

    const max = screen.getByRole("spinbutton", { name: "Max price" });
    await user.clear(max);
    await user.type(max, "1500");
    expect(rowNames()).toHaveLength(2);
    expect(screen.getByRole("combobox", { name: "Price range" })).toHaveValue("custom");
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
    // The untouched price and benchmark rows are skipped, not sent.
    expect(mocks.createSmartphone.mock.calls[0][0]).not.toHaveProperty("prices");
    expect(mocks.createSmartphone.mock.calls[0][0]).not.toHaveProperty("benchmarks");
    expect(screen.getByText("Showing 1–25 of 31")).toBeInTheDocument();
  });

  it("adds a smartphone with a variant price and a benchmark", async () => {
    const user = userEvent.setup();
    mocks.createSmartphone.mockResolvedValue(phone(31, "Nothing", "Phone 3"));
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "+ Add smartphone" }));
    const dialog = screen.getByRole("dialog", { name: "Add smartphone" });
    await user.type(within(dialog).getByRole("textbox", { name: "Brand" }), "Nothing");
    await user.type(within(dialog).getByRole("textbox", { name: "Model" }), "Phone 3");

    const price = within(dialog).getByRole("group", { name: "Price 1" });
    const observed = within(price).getByLabelText("Observed date") as HTMLInputElement;
    expect(observed.value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await user.clear(observed);
    await user.type(observed, "2026-03-01");
    await user.type(within(price).getByRole("spinbutton", { name: "Price" }), "1099");
    await user.selectOptions(within(price).getByRole("combobox", { name: "Currency" }), "USD");
    await user.type(within(price).getByRole("spinbutton", { name: "Variant storage (GB)" }), "256");
    await user.type(within(price).getByRole("spinbutton", { name: "Variant RAM (GB)" }), "8");
    await user.type(within(price).getByRole("textbox", { name: "Source" }), "Shopee");

    const bench = within(dialog).getByRole("group", { name: "Benchmark 1" });
    await user.type(within(bench).getByRole("textbox", { name: "Benchmark name" }), "Geekbench 6 CPU Multi-Core");
    await user.type(within(bench).getByRole("spinbutton", { name: "Score" }), "6800");
    await user.click(within(dialog).getByRole("button", { name: "+ Add benchmark" }));
    expect(within(dialog).getByRole("group", { name: "Benchmark 2" })).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Add smartphone" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const body = mocks.createSmartphone.mock.calls[0][0];
    expect(body.prices).toEqual([
      { observedOn: "2026-03-01", price: 1099, currency: "USD", storageGb: 256, ramGb: 8, source: "Shopee" },
    ]);
    expect(body.benchmarks).toEqual([
      { benchmarkName: "Geekbench 6 CPU Multi-Core", score: 6800, observedOn: expect.any(String), source: null },
    ]);
  });

  it("keeps the Add form open when a started price row has no price", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "+ Add smartphone" }));
    const dialog = screen.getByRole("dialog", { name: "Add smartphone" });
    await user.type(within(dialog).getByRole("textbox", { name: "Brand" }), "Nothing");
    await user.type(within(dialog).getByRole("textbox", { name: "Model" }), "Phone 3");
    const price = within(dialog).getByRole("group", { name: "Price 1" });
    await user.type(within(price).getByRole("textbox", { name: "Source" }), "Shopee");
    await user.click(within(dialog).getByRole("button", { name: "Add smartphone" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Price 1: enter a price above 0.");
    expect(mocks.createSmartphone).not.toHaveBeenCalled();
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

  it("shows read-only price and benchmark history when editing", async () => {
    const user = userEvent.setup();
    mocks.listSmartphonePrices.mockResolvedValue([
      { id: 2, price: 849, currency: "SGD", source: "searchapi", observedAt: "2026-03-01T00:00:00Z", variant: "256 GB · 8 GB RAM" },
      { id: 1, price: "999.00", currency: "SGD", source: "mobileapi", observedAt: "2026-01-01T00:00:00Z", variant: null },
    ]);
    mocks.listSmartphoneBenchmarks.mockResolvedValue([
      { id: 5, benchmarkName: "Geekbench 6 multi-core", score: 6800, unit: "points", higherIsBetter: true, source: null, observedAt: "2026-02-01T00:00:00Z" },
    ]);
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "Edit Brand 01 Model 01" }));
    const history = await screen.findByRole("region", { name: "Pricing and benchmarks" });

    expect(await within(history).findByText(/Current price/)).toHaveTextContent("849.00");
    expect(within(history).getByText("256 GB · 8 GB RAM")).toBeInTheDocument();
    expect(within(history).getByText("Model-level")).toBeInTheDocument();
    expect(within(history).getByText("Geekbench 6 multi-core")).toBeInTheDocument();
    expect(within(history).getByText("6,800 points")).toBeInTheDocument();
    expect(mocks.listSmartphonePrices).toHaveBeenCalledWith(1);
  });

  it("says when an entry has no price or benchmarks, and not on Add", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "Edit Brand 01 Model 01" }));
    expect(await screen.findByText(/No price yet/)).toBeInTheDocument();
    expect(screen.getByText("No benchmark results yet.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "+ Add smartphone" }));
    expect(screen.queryByRole("region", { name: "Pricing and benchmarks" })).not.toBeInTheDocument();
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

describe("AdminCatalogue GPUs", () => {
  const gpus = [
    gpu(1, "NVIDIA", "GeForce RTX 4070", { vramGb: "12" }),
    gpu(2, "AMD", "Radeon RX 7800 XT", { vramGb: "16", architecture: "RDNA 3", memoryBusBits: null }),
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listSmartphones.mockResolvedValue(catalogue);
    mocks.listGpus.mockResolvedValue(gpus);
  });

  it("switches to GPUs and lists them with GPU columns", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("Showing 1–25 of 30");

    await user.click(screen.getByRole("button", { name: "GPUs" }));

    expect(await screen.findByText("Showing 1–2 of 2")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "GPU catalogue" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "VRAM" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Battery" })).not.toBeInTheDocument();
    // Default sort is brand A-Z; the AMD card is missing its memory bus.
    expect(rowNames()).toEqual(["Radeon RX 7800 XT1 spec missing", "GeForce RTX 4070"]);
    expect(mocks.listGpus).toHaveBeenCalledOnce();
  });

  it("filters GPUs by architecture and sorts by VRAM", async () => {
    const user = userEvent.setup();
    renderPage("/admin/catalogue?type=gpus");
    await screen.findByText("Showing 1–2 of 2");

    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "vram-desc");
    expect(rowNames()[0]).toContain("Radeon RX 7800 XT");

    await user.selectOptions(screen.getByRole("combobox", { name: "Architecture" }), "Ada Lovelace");
    expect(rowNames()).toEqual(["GeForce RTX 4070"]);
  });

  it("adds a GPU, sending blank specs as null", async () => {
    const user = userEvent.setup();
    mocks.createGpu.mockResolvedValue(gpu(3, "Intel", "Arc B580"));
    renderPage("/admin/catalogue?type=gpus");
    await screen.findByText("Showing 1–2 of 2");

    await user.click(screen.getByRole("button", { name: "+ Add GPU" }));
    const dialog = screen.getByRole("dialog", { name: "Add GPU" });
    await user.type(within(dialog).getByRole("textbox", { name: "Brand" }), "Intel");
    await user.type(within(dialog).getByRole("textbox", { name: "Model" }), "Arc B580");
    await user.type(within(dialog).getByRole("spinbutton", { name: "VRAM (GB)" }), "12");
    await user.click(within(dialog).getByRole("button", { name: "Add GPU" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(mocks.createGpu).toHaveBeenCalledWith(
      expect.objectContaining({ brand: "Intel", modelName: "Arc B580", vramGb: 12, tdpWatts: null, memoryType: null })
    );
    expect(screen.getByText("Showing 1–3 of 3")).toBeInTheDocument();
  });

  it("adds a GPU with a model-level price and no benchmark fields", async () => {
    const user = userEvent.setup();
    mocks.createGpu.mockResolvedValue(gpu(3, "Intel", "Arc B580"));
    renderPage("/admin/catalogue?type=gpus");
    await screen.findByText("Showing 1–2 of 2");

    await user.click(screen.getByRole("button", { name: "+ Add GPU" }));
    const dialog = screen.getByRole("dialog", { name: "Add GPU" });
    await user.type(within(dialog).getByRole("textbox", { name: "Brand" }), "Intel");
    await user.type(within(dialog).getByRole("textbox", { name: "Model" }), "Arc B580");

    const price = within(dialog).getByRole("group", { name: "Price 1" });
    expect(within(price).queryByRole("spinbutton", { name: "Variant storage (GB)" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "+ Add benchmark" })).not.toBeInTheDocument();
    await user.type(within(price).getByRole("spinbutton", { name: "Price" }), "549.90");
    await user.click(within(dialog).getByRole("button", { name: "Add GPU" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(mocks.createGpu.mock.calls[0][0].prices).toEqual([
      { observedOn: expect.any(String), price: 549.9, currency: "SGD", source: null },
    ]);
  });

  it("edits a GPU and shows where its values came from", async () => {
    const user = userEvent.setup();
    mocks.updateGpu.mockResolvedValue(gpu(1, "NVIDIA", "GeForce RTX 4070", { tdpWatts: 220 }));
    renderPage("/admin/catalogue?type=gpus");
    await screen.findByText("Showing 1–2 of 2");

    await user.click(screen.getByRole("button", { name: "Edit NVIDIA GeForce RTX 4070" }));
    const dialog = screen.getByRole("dialog", { name: "Edit NVIDIA GeForce RTX 4070" });
    expect(within(dialog).getByText(/techspot-gpu-specs, 2026-10-30/)).toBeInTheDocument();
    // GPUs have no price/benchmark history route.
    expect(within(dialog).queryByText(/Price history/i)).not.toBeInTheDocument();
    const tdp = within(dialog).getByRole("spinbutton", { name: "TDP (W)" });
    await user.clear(tdp);
    await user.type(tdp, "220");
    await user.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateGpu).toHaveBeenCalledWith(1, expect.objectContaining({ tdpWatts: 220 })));
  });

  it("deletes a GPU after confirmation", async () => {
    const user = userEvent.setup();
    mocks.deleteGpu.mockResolvedValue(undefined);
    renderPage("/admin/catalogue?type=gpus");
    await screen.findByText("Showing 1–2 of 2");

    await user.click(screen.getByRole("button", { name: "Delete AMD Radeon RX 7800 XT" }));
    await user.click(screen.getByRole("button", { name: "Delete entry" }));

    await waitFor(() => expect(screen.getByText("Showing 1–1 of 1")).toBeInTheDocument());
    expect(mocks.deleteGpu).toHaveBeenCalledWith(2);
  });
});
