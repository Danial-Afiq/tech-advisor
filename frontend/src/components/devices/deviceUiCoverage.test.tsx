import { act, render, renderHook, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DeviceFormModal } from "./DeviceFormModal";
import { DeviceGrid } from "./DeviceGrid";
import { DeviceCard } from "./DeviceCard";
import { PhoneSpecsDropdown } from "./PhoneSpecsDropdown";
import { PriorityPicker } from "./PriorityPicker";
import { UpgradePreferencesModal } from "./UpgradePreferencesModal";
import { ScanOverlay } from "./ScanOverlay";
import { useToasts } from "../ui/useToasts";
import { defaultPrefs, ageLabel, deviceIcon, formatDate, money } from "./deviceOptions";
import { formatSpec, PHONE_SPEC_GROUPS } from "./phoneSpecs";
import type { Device } from "./types";

const device: Device = {
  id: "1",
  type: "Phone",
  brand: "Apple",
  model: "iPhone 13",
  image: "phone.png",
  condition: "Good",
  primary: true,
  use: "Photos",
  purchaseDate: "2025-01-01",
  satisfaction: 80,
  upgradePreferences: defaultPrefs(),
  specs: {
    chipset: "A15",
    ramGb: 6,
    storageGb: 128,
    wirelessChargingWatts: 0,
    softwareSupportYears: 1,
  },
};

describe("device UI coverage", () => {
  it("covers device option and phone-spec helpers", () => {
    expect(deviceIcon("Phone")).toBe("📱");
    expect(deviceIcon("Unknown")).toBe("🔌");
    expect(money(1200)).toContain("1,200");
    expect(formatDate("")).toBe("—");
    expect(formatDate("not-a-date")).toBe("not-a-date");
    expect(formatDate("2025-01-01")).not.toBe("2025-01-01");
    expect(ageLabel("")).toBe("Purchase date unknown");
    expect(ageLabel(new Date().toISOString())).toBe("Less than 1 year old");

    const storage = PHONE_SPEC_GROUPS[0].fields.find((f) => f.key === "storageGb")!;
    const wireless = PHONE_SPEC_GROUPS[2].fields.find((f) => f.key === "wirelessChargingWatts")!;
    const support = PHONE_SPEC_GROUPS[4].fields.find((f) => f.key === "softwareSupportYears")!;
    expect(formatSpec(storage, 2048)).toBe("2 TB");
    expect(formatSpec(wireless, 0)).toBe("Not supported");
    expect(formatSpec(support, 1)).toBe("1 year");
    expect(formatSpec(support, null)).toBeNull();
    expect(formatSpec({ key: "ramGb", label: "RAM", unit: " GB" }, 8)).toBe("8 GB");
    expect(formatSpec({ key: "os", label: "OS" }, "iOS")).toBe("iOS");
  });

  it("creates and edits devices, including save errors", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000001");

    const { rerender } = render(
      <DeviceFormModal device={null} isFirst onClose={onClose} onSave={onSave} />
    );

    await user.type(screen.getByLabelText("Brand"), " Apple ");
    await user.type(screen.getByLabelText("Model / configuration"), " iPhone 16 ");
    fireEvent.change(screen.getByLabelText("Purchase date"), { target: { value: "2026-01-01" } });
    fireEvent.change(screen.getByLabelText(/Current satisfaction/), { target: { value: "90" } });
    await user.type(screen.getByLabelText("Main usage"), "Gaming");
    await user.click(screen.getByRole("button", { name: /Continue to upgrade preferences/ }));

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        brand: "Apple",
        model: "iPhone 16",
        satisfaction: 90,
        primary: true,
      }),
      true
    );

    const failed = vi.fn().mockRejectedValue(new Error("save failed"));
    rerender(<DeviceFormModal device={device} onClose={onClose} onSave={failed} />);
    const model = screen.getByLabelText("Model / configuration");
    await user.clear(model);
    await user.type(model, "iPhone 15");
    await user.click(screen.getByRole("button", { name: "Save device" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("save failed");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("covers grids, cards and callbacks", async () => {
    const user = userEvent.setup();
    const onAdd = vi.fn();
    const onEdit = vi.fn();
    const onPrefs = vi.fn();
    const onSimulate = vi.fn();
    const onRemove = vi.fn();

    const { rerender } = render(
      <DeviceGrid devices={[]} onAdd={onAdd} />
    );
    await user.click(screen.getByRole("button", { name: "Add device" }));
    expect(onAdd).toHaveBeenCalled();

    rerender(
      <DeviceGrid
        devices={[device, { ...device, id: "2", type: "Laptop", image: undefined, primary: false }]}
        onEdit={onEdit}
        onEditPreferences={onPrefs}
        onSimulate={onSimulate}
        onRemove={onRemove}
      />
    );

    await user.click(screen.getAllByRole("button", { name: "Edit device" })[0]);
    await user.click(screen.getAllByRole("button", { name: "Upgrade preferences" })[0]);
    await user.click(screen.getAllByRole("button", { name: /Simulate update/ })[0]);
    await user.click(screen.getAllByRole("button", { name: "Remove" })[0]);
    expect(onEdit).toHaveBeenCalled();
    expect(onPrefs).toHaveBeenCalled();
    expect(onSimulate).toHaveBeenCalled();
    expect(onRemove).toHaveBeenCalled();

    rerender(<DeviceCard device={{ ...device, satisfaction: undefined, condition: undefined }} compact />);
    expect(screen.queryByText("Satisfaction")).not.toBeInTheDocument();
  });

  it("shows phone specs for populated and empty data", () => {
    const { rerender } = render(<PhoneSpecsDropdown specs={device.specs} defaultOpen />);
    expect(screen.getByText(/listed/)).toBeInTheDocument();
    expect(screen.getByText("A15")).toBeInTheDocument();
    rerender(<PhoneSpecsDropdown specs={null} defaultOpen />);
    expect(screen.getByText(/none on file/)).toBeInTheDocument();
  });

  it("selects and deselects priorities", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { rerender } = render(
      <PriorityPicker selected={["Performance"]} onChange={onChange} options={["Performance", "Camera"]} />
    );
    await user.click(screen.getByRole("button", { name: "Performance" }));
    expect(onChange).toHaveBeenCalledWith([]);

    rerender(<PriorityPicker selected={[]} onChange={onChange} options={["Performance", "Camera"]} />);
    await user.click(screen.getByRole("button", { name: "Camera" }));
    expect(onChange).toHaveBeenCalledWith(["Camera"]);
  });

  it("edits upgrade preferences", async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    const onClose = vi.fn();
    render(<UpgradePreferencesModal device={device} isNew onClose={onClose} onSave={onSave} />);

    fireEvent.change(screen.getByLabelText("Maximum budget"), { target: { value: "2500" } });
    await user.click(screen.getByRole("button", { name: "Camera" }));
    fireEvent.change(screen.getByLabelText("Upgrade urgency"), { target: { value: "I like staying current" } });
    fireEvent.change(screen.getByLabelText("Brand flexibility for this replacement"), { target: { value: "No preference" } });
    await user.type(screen.getByLabelText("Current pain points"), "Battery");
    await user.type(screen.getByLabelText("Anything else for this device?"), "No launch day");

    await user.click(screen.getByRole("button", { name: "Save upgrade profile" }));
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      budget: 2500,
      urgency: "I like staying current",
      brandFlex: "No preference",
    }));
    await user.click(screen.getByRole("button", { name: "Skip for now" }));
    expect(onClose).toHaveBeenCalled();
  });

  it("completes scan animation and cleans up timers", () => {
    vi.useFakeTimers();
    const onComplete = vi.fn();
    const { unmount } = render(<ScanOverlay device={device} onComplete={onComplete} />);
    expect(screen.getByText(/Evaluating Apple iPhone 13/)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(4 * 620 + 450);
    });
    expect(onComplete).toHaveBeenCalledTimes(1);
    unmount();
    vi.useRealTimers();
  });

  it("adds and removes toasts", () => {
    vi.useFakeTimers();
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000002");
    const { result } = renderHook(() => useToasts(1000));
    act(() => result.current.pushToast("Saved", "Done", "!"));
    expect(result.current.toasts).toHaveLength(1);
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.toasts).toEqual([]);
    vi.useRealTimers();
  });
});
