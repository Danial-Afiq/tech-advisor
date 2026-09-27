import { Modal } from "../ui/Modal";
import { DeviceThumb } from "./DeviceThumb";
import { money } from "./deviceOptions";
import type { Device } from "./types";

/** "Which owned device is affected?" chooser for a simulated market event. */
export function DevicePickerModal({
  devices,
  onClose,
  onChoose,
}: {
  devices: Device[];
  onClose: () => void;
  onChoose: (device: Device) => void;
}) {
  return (
    <Modal
      eyebrow="Simulated market event"
      title="Which owned device is affected?"
      width="max-w-[560px]"
      onClose={onClose}
    >
      <p className="mt-0 mb-4 text-[13px] leading-[1.55] text-[#8fa0b8]">
        Choose a device. The simulated recommendation will use only that
        device's upgrade budget, priorities, pain points and urgency.
      </p>
      {devices.map((d) => (
        <button
          key={d.id}
          type="button"
          onClick={() => onChoose(d)}
          className="mb-[9px] flex w-full items-center gap-3 rounded-[14px] border border-white/[0.09] bg-[#0a1423] p-[13px] text-left hover:border-[#7c5cff]/45 hover:bg-[#101a2e]"
        >
          <DeviceThumb device={d} size="sm" />
          <div className="min-w-0 flex-1">
            <strong className="block truncate text-[12px]">
              {d.brand} {d.model}
            </strong>
            <span className="mt-[3px] block text-[11px] text-[#8fa0b8]">
              {money(d.upgradePreferences.budget)} ·{" "}
              {d.upgradePreferences.priorities.slice(0, 3).join(" · ") ||
                "No priorities"}
            </span>
          </div>
          <div className="text-[#7f90a8]">→</div>
        </button>
      ))}
    </Modal>
  );
}
