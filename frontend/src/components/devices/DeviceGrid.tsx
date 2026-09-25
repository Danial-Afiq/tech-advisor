import { Button } from "../ui/Button";
import { EmptyState } from "../ui/EmptyState";
import { DeviceCard } from "./DeviceCard";
import { PhoneCard } from "./PhoneCard";
import type { Device } from "./types";

/**
 * Responsive grid of device cards (3 → 2 → 1 columns) — `PhoneCard` for
 * phones, `DeviceCard` for everything else — or an empty state
 * with an "Add device" button when there are none. Omit a handler to hide
 * that button on every card.
 */
export function DeviceGrid({
  devices,
  compact,
  onAdd,
  onEdit,
  onEditPreferences,
  onSimulate,
  onRemove,
}: {
  devices: Device[];
  compact?: boolean;
  onAdd?: () => void;
  onEdit?: (device: Device) => void;
  onEditPreferences?: (device: Device) => void;
  onSimulate?: (device: Device) => void;
  onRemove?: (device: Device) => void;
}) {
  if (devices.length === 0) {
    return (
      <EmptyState
        icon="🔌"
        title="No devices yet"
        description="Add a phone, PC, laptop, monitor, keyboard, mouse or another supported category."
        action={
          onAdd && (
            <Button variant="primary" onClick={onAdd}>
              Add device
            </Button>
          )
        }
      />
    );
  }

  return (
    <div className="grid grid-cols-3 gap-[14px] max-[1100px]:grid-cols-2 max-[620px]:grid-cols-1">
      {devices.map((device) => {
        const Card = device.type === "Phone" ? PhoneCard : DeviceCard;
        return (
          <Card
            key={device.id}
            device={device}
            compact={compact}
            onEdit={onEdit && (() => onEdit(device))}
            onEditPreferences={
              onEditPreferences && (() => onEditPreferences(device))
            }
            onSimulate={onSimulate && (() => onSimulate(device))}
            onRemove={onRemove && (() => onRemove(device))}
          />
        );
      })}
    </div>
  );
}
