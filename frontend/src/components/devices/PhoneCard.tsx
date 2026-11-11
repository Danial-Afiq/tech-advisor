import { DeviceCard } from "./DeviceCard";
import type { DeviceCardProps } from "./DeviceCard";
import { PhoneSpecsDropdown } from "./PhoneSpecsDropdown";
import { specSchemaFor } from "./specSchemas";

/**
 * Card for a phone or GPU in the user's inventory: everything `DeviceCard` shows
 * (image, name, condition, usage, upgrade profile, satisfaction, purchase
 * date, actions) plus a collapsible specifications panel from
 * `device.specs`. Build `device` from API data with `deviceFromApi`.
 */
export function PhoneCard({
  specsOpen = false,
  ...props
}: Omit<DeviceCardProps, "children"> & { specsOpen?: boolean }) {
  return (
    <DeviceCard {...props}>
      <PhoneSpecsDropdown
        specs={props.device.specs}
        schema={specSchemaFor(props.device.type)}
        defaultOpen={specsOpen}
      />
    </DeviceCard>
  );
}
