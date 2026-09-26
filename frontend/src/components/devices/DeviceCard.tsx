import type { ReactNode } from "react";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { Meter } from "../ui/Meter";
import { ConditionBadge } from "./ConditionBadge";
import { DeviceThumb } from "./DeviceThumb";
import { UpgradeProfileSummary } from "./UpgradeProfileSummary";
import { ageLabel, formatDate } from "./deviceOptions";
import type { Device } from "./types";

export type DeviceCardProps = {
  device: Device;
  compact?: boolean;
  onEdit?: () => void;
  onEditPreferences?: () => void;
  onSimulate?: () => void;
  onRemove?: () => void;
  /** Extra content (e.g. a specs dropdown) shown above the action buttons. */
  children?: ReactNode;
};

/**
 * One owned device with its upgrade profile and actions.
 *
 * `compact` hides the meters, purchase line and Remove button, matching the
 * prototype's dashboard version of the card. Omit a handler to hide its button.
 * Missing condition / satisfaction are hidden, not faked.
 */
export function DeviceCard({
  device,
  compact = false,
  onEdit,
  onEditPreferences,
  onSimulate,
  onRemove,
  children,
}: DeviceCardProps) {
  const satisfaction = device.satisfaction;
  const contextFit =
    satisfaction === undefined
      ? undefined
      : Math.max(35, Math.min(96, satisfaction));

  return (
    <Card>
      <div className="card-body min-h-[300px] gap-[14px] p-[18px]">
        <div className="flex justify-between gap-[14px]">
          <div className="flex min-w-0 items-center gap-3">
            <DeviceThumb device={device} />
            <div className="min-w-0">
              <h3 className="text-[16px] font-extrabold">
                {device.brand} {device.model}
              </h3>
              <div className="text-[12px] leading-[1.55] text-[#8fa0b8]">
                {device.type} ·{" "}
                {device.primary ? "Primary device" : "Tracked device"}
              </div>
            </div>
          </div>
          {device.condition && <ConditionBadge condition={device.condition} />}
        </div>

        <div className="text-[12px] leading-[1.55] text-[#8fa0b8]">
          {device.use || "No usage notes"}
        </div>

        <UpgradeProfileSummary prefs={device.upgradePreferences} />

        {!compact && (
          <>
            {satisfaction !== undefined && contextFit !== undefined && (
              <div className="flex flex-col gap-[9px]">
                <Meter label="Satisfaction" value={satisfaction} />
                <Meter label="Context fit" value={contextFit} />
              </div>
            )}
            <div className="text-[12px] leading-[1.55] text-[#8fa0b8]">
              Purchased {formatDate(device.purchaseDate)} ·{" "}
              {ageLabel(device.purchaseDate)}
            </div>
          </>
        )}

        {children}

        <div className="card-actions mt-auto flex-wrap gap-2">
          {onEdit && (
            <Button size="sm" variant="ghost" onClick={onEdit}>
              Edit device
            </Button>
          )}
          {onEditPreferences && (
            <Button size="sm" variant="secondary" onClick={onEditPreferences}>
              Upgrade preferences
            </Button>
          )}
          {onSimulate && (
            <Button size="sm" variant="primary" onClick={onSimulate}>
              ✦ Simulate update
            </Button>
          )}
          {!compact && onRemove && (
            <Button size="sm" variant="danger" onClick={onRemove}>
              Remove
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
