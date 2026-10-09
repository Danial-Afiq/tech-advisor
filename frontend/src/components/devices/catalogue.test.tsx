import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { SmartphoneCatalogueItem } from "../../api/catalogue";
import {
  catalogueSummary,
  diffSpecs,
  formToSpecs,
  matchCatalogue,
  specsToForm,
  withCatalogue,
} from "./catalogue";
import { deviceToRequest } from "./deviceApi";
import { DeviceFormModal } from "./DeviceFormModal";
import type { Device } from "./types";
import type { SmartphoneCatalogue } from "./useSmartphoneCatalogue";

const phone = (
  id: number,
  brand: string,
  modelName: string,
  specs: Partial<SmartphoneCatalogueItem> = {}
): SmartphoneCatalogueItem => ({
  id,
  brand,
  modelName,
  releaseDate: null,
  status: "VERIFIED",
  chipset: null,
  ramGb: null,
  cpuGhz: null,
  storageGb: null,
  batteryMah: null,
  wiredChargingWatts: null,
  wirelessChargingWatts: null,
  displaySizeInches: null,
  refreshRateHz: null,
  weightG: null,
  cameraSpecs: null,
  pixelDensity: null,
  ipRating: null,
  os: null,
  softwareSupportYears: null,
  ...specs,
});

const iphone = phone(12, "Apple", "iPhone 15 Pro", {
  chipset: "Apple A17 Pro",
  ramGb: 8,
  storageGb: 256,
  displaySizeInches: "6.1",
  os: "iOS",
});
const items = [
  iphone,
  phone(13, "Apple", "iPhone 15"),
  phone(20, "Samsung", "Galaxy A15"),
  phone(21, "Samsung", "Galaxy S24", { storageGb: 1024 }),
];
const ready: SmartphoneCatalogue = { status: "ready", items };
const modelBox = () =>
  screen.getByRole("combobox", { name: "Model / configuration" });

describe("catalogue helpers", () => {
  it("matches typed words against the start of name words", () => {
    expect(matchCatalogue(items, "iphone 15").map((i) => i.id)).toEqual([12, 13]);
    expect(matchCatalogue(items, "15").map((i) => i.id)).toEqual([12, 13]);
    expect(matchCatalogue(items, "galaxy s2").map((i) => i.id)).toEqual([21]);
    expect(matchCatalogue(items, "samsung", "", 1)).toHaveLength(1);
    expect(matchCatalogue(items, "  ")).toEqual([]);
    expect(matchCatalogue(items, "pixel")).toEqual([]);
  });

  it("ranks items from the typed brand first", () => {
    const mixed = [phone(1, "Samsung", "Note 15"), phone(2, "Apple", "iPhone 15")];
    expect(matchCatalogue(mixed, "15", "apple").map((i) => i.id)).toEqual([2, 1]);
  });

  it("summarises, converts and diffs specs", () => {
    expect(catalogueSummary(iphone)).toBe("Apple A17 Pro · 256 GB · 8 GB RAM");
    expect(catalogueSummary(items[3])).toBe("1 TB");

    const form = specsToForm({ ramGb: 8, os: "iOS", chipset: null });
    expect(form.ramGb).toBe("8");
    expect(form.chipset).toBe("");
    expect(
      formToSpecs({ ...form, ramGb: " 12 ", os: " Android ", batteryMah: "abc" })
    ).toEqual({ ramGb: 12, os: "Android" });

    expect(
      diffSpecs({ ramGb: 12, os: "iOS", storageGb: 256 }, { ramGb: 8, os: "iOS", storageGb: 256 })
    ).toEqual({ ramGb: 12 });
  });

  it("fills a linked device's specs from the catalogue, keeping overrides", () => {
    const device = {
      id: "1",
      productId: 12,
      type: "Other",
      brand: "Apple",
      model: "iPhone 15 Pro",
      primary: false,
      use: "",
      purchaseDate: "",
      specOverrides: { storageGb: 512 },
    } as Device;
    expect(withCatalogue(device, undefined)).toBe(device);
    expect(withCatalogue(device, iphone)).toMatchObject({
      type: "Phone",
      specs: { chipset: "Apple A17 Pro", storageGb: 512, ramGb: 8 },
    });
  });

  it("sends overrides keyed by phone column name", () => {
    const device = {
      id: "1",
      productId: 12,
      type: "Phone",
      brand: "Apple",
      model: "iPhone 15 Pro",
      primary: false,
      use: "",
      purchaseDate: "",
      specOverrides: { storageGb: 512, displaySizeInches: 6.1 },
    } as Device;
    expect(deviceToRequest(device)).toMatchObject({
      productId: 12,
      customName: null,
      specOverrides: '{"storage_gb":512,"display_size_inches":6.1}',
    });
  });
});

describe("DeviceFormModal catalogue search", () => {
  const renderForm = (catalogue?: SmartphoneCatalogue, device: Device | null = null) => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <DeviceFormModal
        device={device}
        catalogue={catalogue}
        onClose={vi.fn()}
        onSave={onSave}
      />
    );
    return onSave;
  };

  it("suggests matching catalogue devices while typing", async () => {
    const user = userEvent.setup();
    renderForm(ready);

    const model = modelBox();
    await user.type(model, "iphone");
    const options = within(screen.getByRole("listbox")).getAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual([
      "Apple iPhone 15 ProApple A17 Pro · 256 GB · 8 GB RAM",
      "Apple iPhone 15",
    ]);
    expect(model).toHaveAttribute("aria-expanded", "true");

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("prefills specs from a picked device and saves only edited ones as overrides", async () => {
    const user = userEvent.setup();
    const onSave = renderForm(ready);

    await user.type(modelBox(), "15 pro");
    await user.click(screen.getByRole("option", { name: /iPhone 15 Pro/ }));

    expect(screen.getByLabelText("Device type")).toHaveValue("Phone");
    expect(screen.getByLabelText("Brand")).toHaveValue("Apple");
    expect(modelBox()).toHaveValue("iPhone 15 Pro");
    expect(screen.getByLabelText("Chipset")).toHaveValue("Apple A17 Pro");
    expect(screen.getByLabelText("Storage (GB)")).toHaveValue(256);
    expect(screen.getByLabelText("Screen size (in)")).toHaveValue(6.1);
    expect(screen.getByText(/Matched to/)).toHaveTextContent("Apple iPhone 15 Pro");

    const storage = screen.getByLabelText("Storage (GB)");
    await user.clear(storage);
    await user.type(storage, "512");
    expect(screen.getByLabelText(/Storage \(GB\)/)).toHaveAccessibleName("Storage (GB) Edited");

    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));
    const saved = onSave.mock.calls[0][0] as Device;
    expect(saved).toMatchObject({
      productId: 12,
      type: "Phone",
      specOverrides: { storageGb: 512 },
      specs: { chipset: "Apple A17 Pro", storageGb: 512, ramGb: 8 },
    });
  });

  it("picks with the keyboard", async () => {
    const user = userEvent.setup();
    const onSave = renderForm(ready);

    await user.type(modelBox(), "galaxy");
    await user.keyboard("{ArrowDown}{ArrowUp}{ArrowUp}{Enter}");
    expect(modelBox()).toHaveValue("Galaxy S24");
    expect(onSave).not.toHaveBeenCalled(); // Enter picked instead of submitting
  });

  it("falls back to manual entry when nothing matches or a pick is undone", async () => {
    const user = userEvent.setup();
    const onSave = renderForm(ready);

    await user.selectOptions(screen.getByLabelText("Device type"), "Phone");
    await user.type(screen.getByLabelText("Brand"), "Nothing");
    await user.type(modelBox(), "Phone 2a");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByText(/Not in the product catalogue/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("RAM (GB)"), "12");

    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));
    expect(onSave.mock.calls[0][0]).toMatchObject({
      productId: null,
      brand: "Nothing",
      model: "Phone 2a",
      specOverrides: { ramGb: 12 },
    });
  });

  it("unlinks but keeps the specs when the user chooses manual entry", async () => {
    const user = userEvent.setup();
    const onSave = renderForm(ready);

    await user.type(modelBox(), "15 pro");
    await user.click(screen.getByRole("option", { name: /iPhone 15 Pro/ }));
    await user.click(screen.getByRole("button", { name: "Enter manually instead" }));
    expect(screen.queryByText(/Matched to/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Chipset")).toHaveValue("Apple A17 Pro");

    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));
    expect(onSave.mock.calls[0][0]).toMatchObject({
      productId: null,
      specOverrides: { chipset: "Apple A17 Pro", storageGb: 256 },
    });
  });

  it("explains a loading or unavailable catalogue without an error", async () => {
    const user = userEvent.setup();
    renderForm({ status: "loading", items: [] });
    await user.type(modelBox(), "iphone");
    expect(screen.getByText(/Loading the product catalogue/)).toBeInTheDocument();
  });

  it("works without a catalogue at all", async () => {
    const user = userEvent.setup();
    renderForm({ status: "unavailable", items: [] });
    await user.type(modelBox(), "iphone");
    expect(screen.getByText(/aren't available right now/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("keeps earlier overrides when editing a linked device without the catalogue", async () => {
    const user = userEvent.setup();
    const device = {
      id: "1",
      productId: 99,
      type: "Phone",
      brand: "Google",
      model: "Pixel 9",
      primary: true,
      use: "",
      purchaseDate: "",
      upgradePreferences: undefined,
      specs: { ramGb: 12, storageGb: 256 },
      specOverrides: { storageGb: 256 },
    } as unknown as Device;
    const onSave = renderForm(undefined, device);

    expect(screen.getByLabelText("RAM (GB)")).toHaveValue(12);
    const ram = screen.getByLabelText("RAM (GB)");
    await user.clear(ram);
    await user.type(ram, "16");
    await user.selectOptions(screen.getByLabelText("Condition"), "Fair");
    await user.click(screen.getByRole("button", { name: "Save device" }));
    expect(onSave.mock.calls[0][0]).toMatchObject({
      productId: 99,
      specOverrides: { storageGb: 256, ramGb: 16 },
    });
  });
});
