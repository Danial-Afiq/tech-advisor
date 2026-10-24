import type { ReactNode } from "react";
import { Button } from "../ui/Button";
import { Card } from "../ui/Card";
import { FieldHint } from "../ui/Form";
import { Meter } from "../ui/Meter";
import { ConditionBadge } from "./ConditionBadge";
import { DeviceThumb } from "./DeviceThumb";
import { UpgradeProfileSummary } from "./UpgradeProfileSummary";
import { ageLabel, formatDate } from "./deviceOptions";
import type { Device } from "./types";

export type DeviceCardProps = {
  device: Device;
  onEdit?: () => void;
  onEditPreferences?: () => void;
  onRemove?: () => void;
  /** Extra content (e.g. a specs dropdown) shown above the action buttons. */
  children?: ReactNode;
};

/**
 * One owned device with its upgrade profile and actions.
 *
 * Omit a handler to hide its button.
 * Missing condition / satisfaction are hidden, not faked.
 */
export function DeviceCard({
  device,
  onEdit,
  onEditPreferences,
  onRemove,
  children,
}: DeviceCardProps) {
  const satisfaction = device.satisfaction;
  // The backend never evaluates a device without a budget (root AGENTS.md §27.10).
  const needsBudget = Boolean(onEditPreferences) && device.upgradePreferences.budget == null;

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
        {needsBudget && (
          <FieldHint>Set a budget in Upgrade preferences to get recommendations for this device.</FieldHint>
        )}

        {satisfaction !== undefined && (
          <Meter label="Satisfaction" value={satisfaction} />
        )}
        <div className="text-[12px] leading-[1.55] text-[#8fa0b8]">
          Purchased {formatDate(device.purchaseDate)} ·{" "}
          {ageLabel(device.purchaseDate)}
        </div>

        {children}

        <div className="card-actions mt-auto flex-wrap gap-2">
          {onEdit && (
            <Button size="sm" variant="ghost" onClick={onEdit}>
              Edit device
            </Button>
          )}
          {onEditPreferences && (
            <Button size="sm" variant={needsBudget ? "primary" : "secondary"} onClick={onEditPreferences}>
              Upgrade preferences
            </Button>
          )}
          {onRemove && (
            <Button size="sm" variant="danger" onClick={onRemove}>
              Remove
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
